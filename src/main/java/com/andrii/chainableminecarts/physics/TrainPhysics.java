package com.andrii.chainableminecarts.physics;

import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.rail.TrackWalk;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartFurnace;

/**
 * Moves chained carts as trains. Leads only say which carts belong together; there are no rope forces. Every cart
 * first moves on its own as vanilla does (powered rails, slopes, drag, pushes, collisions with other carts). Then,
 * at the end of the server tick:
 * <ol>
 * <li>each train gets one shared speed, the mass-weighted average of its carts' speeds along the train. A push or
 * boost on any cart moves the whole train, and when carts pull in opposite directions the faster one wins but the
 * train as a whole ends up slower;</li>
 * <li>furnace carts with their brakes on slow the train down and hold it; fuelled ones drive it towards a top speed. Each furnace pulls a set number of other carts at
 * full speed; every cart beyond that lowers the top speed, down to not moving at all. Furnaces facing opposite ways
 * cancel out. A furnace switched off this tick stops its whole train instead;</li>
 * <li>a small, capped correction pulls together a linked pair whose gap is wider than the lead allows, or pushes
 * apart one whose gap is narrower than the minimum. In between the lead is slack, so a stopping train can bunch up
 * a little instead of the spacing being forced back and forth.</li>
 * </ol>
 * A train can't move towards a wall that any of its cars is up against: the whole train stops at it, rather than
 * the cars behind carrying on into the one at the wall. A car at a wall takes no part in fixing the spacing either;
 * its neighbour moves the whole way instead. And the spacing fixes are never taken as the train's own speed next
 * tick: after the cars behind have been moved back off a car at a wall, the train mustn't keep rolling back.
 * <p>
 * Which way a car faces, and how far apart two cars are, are both measured by following the track from one car to
 * the next rather than in a straight line between them. Round a U or a tight loop the line between two cars says
 * nothing useful: they can sit a block apart side by side while being several blocks apart along the rails, with
 * the line between them square to both. Falls back to the straight line where the track can't be followed.
 * <p>
 * Only motion is changed, so vanilla movement keeps every cart on its rail next tick.
 */
public final class TrainPhysics
{
    /** Largest speed used to fix one pair's spacing, in blocks per tick. */
    private static final double MAX_SPACING_CORRECTION = 0.1D;
    /** The smallest spacing fix that still moves a car: what's left after drag and rolling resistance must beat restSpeed. */
    private static double minSpacingCorrection()
    {
        return (ModConfig.carts.restSpeed + ModConfig.carts.rollingResistance) / 0.96D + 0.001D;
    }

    /** Spacing this close to right is left alone, so a settled train isn't nudged back and forth forever. */
    private static final double SPACING_TOLERANCE = 0.02D;

    /** Each car's spacing fix last tick, as motion: taken back out of its speed before working out the train's. */
    private static final Map<EntityMinecart, double[]> LAST_CORRECTION = new WeakHashMap<>();

    private TrainPhysics()
    {
    }

    /** One cart in a train, with its forward direction (towards the next cart) and speed along it. */
    private static final class Car
    {
        final EntityMinecart cart;
        final List<Car> neighbours = new ArrayList<>();
        int order = -1;
        /** The forward direction if the cart is on a rail (it can only move along it), otherwise null. */
        double[] axis;
        double forwardX;
        double forwardZ;
        double speed;
        /** Its own speed along the train: without last tick's spacing fix, and none into a wall it's against. */
        double ownSpeed;
        double correction;
        /** Up against a wall ahead of it (forward along the train), or behind it. */
        boolean blockedAhead;
        boolean blockedBehind;
        /** Distance along the track to the next car in the train, or NaN where the track couldn't be followed. */
        double gapAhead = Double.NaN;

        Car(EntityMinecart cart)
        {
            this.cart = cart;
        }
    }

    /**
     * @param links pairs of linked carts
     * @param drivingFurnaces fuelled furnace carts on rails; ones not in any link are driven as a train of one
     * @param brakingFurnaces switched-off furnace carts with fuel left, which brake their trains
     */
    public static void solve(List<EntityMinecart[]> links, List<EntityMinecartFurnace> drivingFurnaces, List<EntityMinecartFurnace> brakingFurnaces)
    {
        if (links.isEmpty() && drivingFurnaces.isEmpty() && brakingFurnaces.isEmpty())
        {
            return;
        }

        Map<EntityMinecart, Car> cars = new IdentityHashMap<>();
        Set<EntityMinecart> driving = Collections.newSetFromMap(new IdentityHashMap<>());
        driving.addAll(drivingFurnaces);
        Set<EntityMinecart> braking = Collections.newSetFromMap(new IdentityHashMap<>());
        braking.addAll(brakingFurnaces);

        for (EntityMinecart[] link : links)
        {
            Car a = cars.computeIfAbsent(link[0], Car::new);
            Car b = cars.computeIfAbsent(link[1], Car::new);

            if (!a.neighbours.contains(b))
            {
                a.neighbours.add(b);
                b.neighbours.add(a);
            }
        }

        for (EntityMinecartFurnace furnace : drivingFurnaces)
        {
            cars.computeIfAbsent(furnace, Car::new);
        }

        for (EntityMinecartFurnace furnace : brakingFurnaces)
        {
            cars.computeIfAbsent(furnace, Car::new);
        }

        for (Car car : cars.values())
        {
            if (car.order == -1)
            {
                moveTrain(collectTrain(car), driving, braking);
            }
        }
    }

    /**
     * All cars connected to {@code start}, numbered from one end of the train to the other. The numbering says
     * which way is "forward" along the train.
     */
    private static List<Car> collectTrain(Car start)
    {
        // Walk to an end of the train first (a car with one neighbour), so numbering runs end to end
        Car end = start;
        Map<Car, Boolean> visited = new IdentityHashMap<>();
        Deque<Car> queue = new ArrayDeque<>();
        queue.add(start);
        visited.put(start, true);

        while (!queue.isEmpty())
        {
            Car car = queue.poll();

            if (car.neighbours.size() == 1)
            {
                end = car;
                break;
            }

            for (Car next : car.neighbours)
            {
                if (visited.put(next, true) == null)
                {
                    queue.add(next);
                }
            }
        }

        List<Car> train = new ArrayList<>();
        end.order = 0;
        queue.clear();
        queue.add(end);

        while (!queue.isEmpty())
        {
            Car car = queue.poll();
            train.add(car);

            for (Car next : car.neighbours)
            {
                if (next.order == -1)
                {
                    next.order = train.size() + queue.size();
                    queue.add(next);
                }
            }
        }

        return train;
    }

    private static void moveTrain(List<Car> train, Set<EntityMinecart> driving, Set<EntityMinecart> braking)
    {
        double momentum = 0.0D;
        double totalMass = 0.0D;

        orient(train);

        boolean trainBlockedAhead = false;
        boolean trainBlockedBehind = false;

        for (Car car : train)
        {
            car.speed = CartBody.speed(car.cart, car.axis, car.forwardX, car.forwardZ);
            car.blockedAhead = CartBody.blockedAlong(car.cart, car.forwardX, car.forwardZ);
            car.blockedBehind = CartBody.blockedAlong(car.cart, -car.forwardX, -car.forwardZ);
            trainBlockedAhead |= car.blockedAhead;
            trainBlockedBehind |= car.blockedBehind;
            car.ownSpeed = car.speed;
            double[] fix = LAST_CORRECTION.remove(car.cart);

            if (fix != null)
            {
                car.ownSpeed -= fix[0] * car.forwardX + fix[1] * car.forwardZ;
            }

            if (car.ownSpeed > 0.0D && car.blockedAhead || car.ownSpeed < 0.0D && car.blockedBehind)
            {
                car.ownSpeed = 0.0D;
            }

            double mass = CartBody.mass(car.cart);
            momentum += mass * car.ownSpeed;
            totalMass += mass;
        }

        // No car in the train can go faster than its rail allows, so neither can the train
        double speedCap = Double.POSITIVE_INFINITY;

        for (Car car : train)
        {
            speedCap = Math.min(speedCap, CartBody.railSpeedCap(car.cart));
        }

        double sharedSpeed = clamp(drive(train, driving, momentum / totalMass), speedCap);
        sharedSpeed = brake(train, braking, sharedSpeed);

        // Up against a wall: no moving that way
        if (sharedSpeed > 0.0D && trainBlockedAhead || sharedSpeed < 0.0D && trainBlockedBehind)
        {
            sharedSpeed = 0.0D;
        }

        // Spacing: a pair outside the allowed gap range moves back into it, the rear car moving forward and the
        // front car back by the same amount (or the other way round)
        double maxGap = ModConfig.trains.maxRopeGap;
        double minGap = Math.min(ModConfig.trains.minRopeGap, maxGap);

        for (Car car : train)
        {
            for (Car next : car.neighbours)
            {
                if (next.order > car.order)
                {
                    double gap = gapBetween(car, next) - (car.cart.width + next.cart.width) * 0.5D;
                    double error = gap > maxGap + SPACING_TOLERANCE ? gap - maxGap
                        : gap < minGap - SPACING_TOLERANCE ? gap - minGap : 0.0D;
                    double fix = clamp(ModConfig.trains.stiffness * error * 0.5D, MAX_SPACING_CORRECTION);

                    // Never so small that a tick's drag and rolling resistance eat it all: the cars would never move,
                    // left with a sliver of speed, forever
                    if (error != 0.0D && Math.abs(fix) < minSpacingCorrection())
                    {
                        fix = Math.copySign(minSpacingCorrection(), fix);
                    }
                    boolean carStuck = fix > 0.0D ? car.blockedAhead : fix < 0.0D && car.blockedBehind;
                    boolean nextStuck = fix > 0.0D ? next.blockedBehind : fix < 0.0D && next.blockedAhead;

                    // A car against a wall can't take its share: the other takes the lot
                    if (!carStuck)
                    {
                        car.correction += nextStuck ? fix * 2.0D : fix;
                    }

                    if (!nextStuck)
                    {
                        next.correction -= carStuck ? fix * 2.0D : fix;
                    }
                }
            }
        }

        for (Car car : train)
        {
            double speed = clamp(sharedSpeed + clamp(car.correction, MAX_SPACING_CORRECTION * 2.0D), speedCap);

            if (speed > 0.0D && car.blockedAhead || speed < 0.0D && car.blockedBehind)
            {
                speed = 0.0D;
            }

            CartBody.setSpeed(car.cart, car.axis, car.forwardX, car.forwardZ, car.speed, speed);
            double fix = speed - sharedSpeed;

            if (fix != 0.0D)
            {
                LAST_CORRECTION.put(car.cart, new double[] {fix * car.forwardX, fix * car.forwardZ});
            }
        }
    }

    /**
     * Works out which way each car faces by following the track from one car to the next, which also gives the
     * distance between them. The lead car is settled by which way along the track the next car lies, then each car
     * hands its direction of travel on to the next. If the track can't be followed all the way down the train (a car
     * off the rails, broken track, a branching train) the whole train falls back to the straight lines between its
     * cars instead: mixing the two could leave cars disagreeing about which way is forward.
     */
    private static void orient(List<Car> train)
    {
        Car head = train.get(0);
        double[] along = TrackWalk.direction(head.cart);

        if (along != null && train.size() == 1)
        {
            // On its own: run along the track. Which way round doesn't matter for a single car, as long as its
            // speed is read the same way it's written.
            face(head, along[0], along[1]);
            return;
        }

        boolean followed = along != null;

        if (followed)
        {
            Car next = train.get(1);
            TrackWalk.Step ahead = TrackWalk.to(head.cart, next.cart, along[0], along[1]);
            TrackWalk.Step behind = ahead != null ? null : TrackWalk.to(head.cart, next.cart, -along[0], -along[1]);
            double sense = ahead != null ? 1.0D : -1.0D;
            face(head, along[0] * sense, along[1] * sense);
            followed = carryOn(head, next, ahead != null ? ahead : behind);
        }

        for (int i = 1; followed && i + 1 < train.size(); ++i)
        {
            Car car = train.get(i);
            followed = carryOn(car, train.get(i + 1), TrackWalk.to(car.cart, train.get(i + 1).cart, car.forwardX, car.forwardZ));
        }

        if (!followed)
        {
            for (Car car : train)
            {
                car.gapAhead = Double.NaN;
                findForward(car);
            }
        }
    }

    /** Hands the direction of travel on to the next car, along with how far along the track it is. */
    private static boolean carryOn(Car car, Car next, @Nullable TrackWalk.Step step)
    {
        if (step == null)
        {
            return false;
        }

        car.gapAhead = step.distance;
        face(next, step.dirX, step.dirZ);
        return true;
    }

    private static void face(Car car, double dirX, double dirZ)
    {
        car.forwardX = dirX;
        car.forwardZ = dirZ;
        car.axis = new double[] {dirX, dirZ};
    }

    /** How far apart two cars are along the track, or in a straight line where the track couldn't be followed. */
    private static double gapBetween(Car car, Car next)
    {
        if (!Double.isNaN(car.gapAhead))
        {
            return car.gapAhead;
        }

        double dx = next.cart.posX - car.cart.posX;
        double dz = next.cart.posZ - car.cart.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Slows the train by however many of its furnace carts have their brakes on. They bite gradually, so a train
     * carries its speed for a moment instead of stopping dead, and they hold it once stopped.
     */
    private static double brake(List<Car> train, Set<EntityMinecart> braking, double speed)
    {
        int brakes = 0;

        for (Car car : train)
        {
            if (braking.contains(car.cart))
            {
                ++brakes;
            }
        }

        if (brakes == 0)
        {
            return speed;
        }

        double slowed = Math.abs(speed) - brakes * ModConfig.furnace.furnaceBrakePower;
        return slowed <= 0.0D ? 0.0D : Math.copySign(slowed, speed);
    }

    /**
     * Speeds the train up towards what its furnaces can manage. Each furnace driving the same way as the majority
     * pulls {@code cartsPerFurnace} other carts at full speed; each cart beyond that takes
     * {@code furnaceSlowdownPerCart} off the top speed. Furnaces never brake a train that's already faster.
     *
     * @param speed the train's shared speed, positive forward along the train
     * @return the new shared speed
     */
    private static double drive(List<Car> train, Set<EntityMinecart> driving, double speed)
    {
        int forward = 0;
        int backward = 0;
        int load = 0;

        for (Car car : train)
        {
            if (driving.contains(car.cart))
            {
                double[] direction = FurnaceEngine.direction((EntityMinecartFurnace)car.cart);

                if (direction[0] * car.forwardX + direction[1] * car.forwardZ >= 0.0D)
                {
                    ++forward;
                }
                else
                {
                    ++backward;
                }
            }
            else
            {
                ++load;
            }
        }

        int pulling = Math.abs(forward - backward);

        if (pulling == 0)
        {
            return speed;
        }

        double sense = forward > backward ? 1.0D : -1.0D;
        int extraCarts = Math.max(0, load - pulling * ModConfig.furnace.cartsPerFurnace);
        double topSpeed = ModConfig.furnace.furnaceSpeed - extraCarts * ModConfig.furnace.furnaceSlowdownPerCart;
        double along = speed * sense;

        if (topSpeed > 0.0D && along < topSpeed)
        {
            along = Math.min(topSpeed, along + ModConfig.furnace.furnacePower);
        }

        return along * sense;
    }

    /**
     * The direction along the cart's rail that points towards the higher-numbered end of the train. It's taken
     * from the link that lines up best with the rail, so it doesn't depend on how the rest of the train bends.
     */
    private static void findForward(Car car)
    {
        car.axis = CartBody.railAxis(car.cart);
        double bestX = 0.0D;
        double bestZ = 0.0D;
        double bestAlignment = -1.0D;

        for (Car other : car.neighbours)
        {
            double sign = other.order > car.order ? 1.0D : -1.0D;
            double dx = (other.cart.posX - car.cart.posX) * sign;
            double dz = (other.cart.posZ - car.cart.posZ) * sign;
            double length = Math.sqrt(dx * dx + dz * dz);

            if (length < 1.0E-4D)
            {
                continue;
            }

            dx /= length;
            dz /= length;
            double alignment = car.axis == null ? 1.0D : Math.abs(car.axis[0] * dx + car.axis[1] * dz);

            if (alignment > bestAlignment)
            {
                bestAlignment = alignment;
                bestX = dx;
                bestZ = dz;
            }
        }

        if (bestAlignment < 0.0D)
        {
            // Carts on top of each other: any direction will do
            bestX = car.axis == null ? 1.0D : car.axis[0];
            bestZ = car.axis == null ? 0.0D : car.axis[1];
        }

        if (car.axis == null)
        {
            car.forwardX = bestX;
            car.forwardZ = bestZ;
        }
        else
        {
            double sign = car.axis[0] * bestX + car.axis[1] * bestZ < 0.0D ? -1.0D : 1.0D;
            car.forwardX = car.axis[0] * sign;
            car.forwardZ = car.axis[1] * sign;
            // Speeds read and written along the rail are then signed forward along the train
            car.axis = new double[] {car.forwardX, car.forwardZ};
        }
    }

    private static double clamp(double value, double limit)
    {
        return Math.max(-limit, Math.min(limit, value));
    }
}
