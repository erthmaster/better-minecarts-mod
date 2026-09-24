# Better Minecarts

A Minecraft 1.12.2 Forge mod that makes minecarts feel like real rolling stock while keeping them vanilla at heart:
heavy, solid carts that follow curves smoothly, trains of carts chained with leads, furnace-cart engines that run
on distance, and two new rails for junctions.

- **Mod id:** `better_minecarts`
- **Version:** 0.1 (pre-release)
- **Author:** erthmaster
- **Requires:** Minecraft 1.12.2, Forge 14.23.5.2864 or newer. Install it on both the server and every client.

## Features

### Heavier, more solid carts

- **Weight instead of bounce.** Carts collide rigidly: a rolling cart shoves a resting one along instead of bouncing
  off it, momentum passes down a row of carts, and carts never pass through each other.
- **They come to a stop.** A small, constant rolling resistance slows carts on ordinary rails until they stop
  completely, instead of creeping on forever. Powered rails still boost them as usual.
- **Every cart moves the same.** Vanilla drags a ridden cart far less than an empty one, moves it only three
  quarters of its speed, and lets chest and hopper carts roll many times further. Here every cart rolls exactly like an
  empty one: what it carries or who rides it makes no difference.
- **An honest speed limit.** No cart goes faster than its rail allows (0.4 blocks per tick on vanilla rails), on
  straights and curves alike. Vanilla lets carts cover about 40% more ground on a diagonal.
- **Walls stop carts.** A cart running into a block stops with its front against it, and a train stops there whole,
  without bouncing back off it.
- **Carts only meet on the same track.** Collisions between carts on the same line are measured along the track, so
  carts on the two legs of a U-turn or on parallel tracks pass each other without touching.

### Solid carts

- Players and mobs can't walk through a minecart: it is solid like a block, and you can stand on it.
- Walking into a cart pushes it along its rail. How hard is set by `entityPushFactor`.
- If you end up inside a cart anyway (after getting out of it, for example), you're moved out the shortest way.

### Smooth curves

- Carts follow the actual curve of a turning rail and turn smoothly, instead of cutting across it diagonally and
  snapping between angles. This works on U-turns, loops and S-bends too.
- Carts are drawn moving along the track between the position updates the server sends, so they don't cut corners,
  jump between the legs of a U-turn, or sink into walls and other carts while you wait for the next update.
- Whoever rides a cart turns with it through curves, the way a boat turns its passengers.

### Trains: chaining carts with leads

- **Put a lead on a cart:** right-click the cart with a lead. You now hold the lead, just as with a mob.
- **Chain it to another cart:** while holding the lead, right-click another cart. The lead is tied between the two,
  and the cart you clicked becomes the leader. A cart can be tied to several others, so trains can be as long as you
  like.
- **Let go:** right-click a cart whose lead you hold. The lead drops as an item (unless you're in creative).
- **Unchain a cart:** sneak and right-click it with an empty hand. Its leads to other carts come off.
- **Leads snap** if their ends get more than `breakDistance` blocks apart, when a cart is destroyed, or when a cart
  goes to another dimension. By default the lead drops as an item.

Chained carts move as one train:

- The whole train shares one speed. A push or a boost on any cart moves all of them. When carts pull in opposite
  directions, the faster one wins but the train ends up slower.
- The gap between chained carts is kept between `minRopeGap` and `maxRopeGap`. Between the two the lead is slack, so
  a stopping train can bunch up a little.
- Spacing is measured along the track, so trains keep their shape through curves, U-turns and loops.

### Furnace carts as engines

- **Fuel is distance.** Each coal (or charcoal) lasts 720 blocks of travel, used up only while the cart actually
  moves. A furnace cart holds up to 8 coal. Vanilla fuel burns by time, even while the cart is standing still.
- **A fixed direction.** A furnace cart drives the way you were facing when you placed it, with the furnace's front
  pointing that way, and keeps that direction along the track through every curve. Nothing turns it round short of
  breaking it and placing it again.
- **Controls:**
  - right-click with coal adds fuel and starts the engine;
  - right-click with anything else puts the brakes on, and the train slows to a stop with some inertia;
  - right-click again to start it once more.
- **Pulling power.** Each furnace cart pulls 19 other carts at full speed. Every cart beyond that lowers the train's
  top speed, down to not moving at all. More furnaces pull more. Furnaces facing opposite ways along a train cancel
  out.
- **An idle furnace cart is just a cart.** Once stopped, it rolls and weighs exactly like any other.

### New rails

**Intersection Rail**

A crossing: carts go straight through it in either direction, north–south or east–west.

Crafting (shaped):

```
 R
RIR
 R
```

R = Rail, I = Iron Ingot. Makes 1.

**Double Turn Rail**

A switch where a side road joins a main road:

- Carts travelling along the main road always go straight through.
- Carts coming in from the side road turn onto the main road, to one side or the other. A redstone signal (a lever,
  for example) decides which. The rail's texture flips to show the way it's set.
- Placed at a T of rails, the side road is picked up automatically. Otherwise you are taken to be standing on the side
  road, looking at the main road.

Crafting (shaped):

```
RLR
 R
```

R = Rail, L = Lever. Makes 1.

### Smarter rail placement

- A new rail is shaped by the track around it and the way you're looking:
  - a turn wherever it can join two pieces of track at right angles;
  - otherwise the straight that joins the most track;
  - otherwise straight ahead the way you're looking.
- **Sneak while placing** to always lay a straight rail the way you're looking.
- Placing a rail never breaks a connection that already exists. Neighbouring rails with a free end turn to meet the
  new one.

## Configuration

Settings are in `config/better_minecarts.cfg`, or in game under *Mods → Better Minecarts → Config*. They come in four
groups; Forge lists each group's settings alphabetically.

### `carts`: movement and collisions

| Setting | Default | What it does |
|---|---|---|
| `heavyPhysics` | `true` | Heavy, non-bouncy collisions and rolling resistance. Turn off if another mod (e.g. Railcraft) installs its own minecart collision handler. Needs a restart. |
| `rollingResistance` | `0.002` | Constant slowdown per tick on unpowered rails, in blocks/tick. |
| `restSpeed` | `0.003` | Carts slower than this (blocks/tick) come to a full stop. |
| `restitution` | `0.0` | How much carts bounce apart when they collide (0 = not at all, 1 = fully elastic). |
| `solidCarts` | `true` | Carts are solid for players and mobs. |
| `entityPushFactor` | `0.4` | How hard players and mobs push carts, as a share of vanilla's push. |

### `curves`

| Setting | Default | What it does |
|---|---|---|
| `smoothCurves` | `true` | Carts follow the curve of turning rails and turn smoothly. |
| `riderTurnsWithCart` | `true` | Riders turn with the cart through curves. |

### `furnace`

| Setting | Default | What it does |
|---|---|---|
| `distanceFurnaceFuel` | `true` | Furnace carts run on distance with a fixed direction. Off: vanilla furnace carts. |
| `furnaceBlocksPerCoal` | `720` | Blocks travelled per coal. |
| `furnaceSpeed` | `0.5` | Top speed of a furnace-driven train, in blocks/tick. Rails cap every cart at 0.4, so anything above that acts as 0.4. |
| `furnacePower` | `0.02` | Speed a furnace-driven train gains per tick. |
| `furnaceBrakePower` | `0.007` | Speed a furnace cart's brakes take off its train per tick. Lower means a longer stop; 0 means it just coasts. |
| `cartsPerFurnace` | `19` | Carts each furnace cart pulls at full speed. |
| `furnaceSlowdownPerCart` | `0.1` | How much the top speed drops for each cart beyond that. |

### `trains`

| Setting | Default | What it does |
|---|---|---|
| `maxRopeGap` | `0.6` | Largest gap between two chained carts' edges, in blocks. Farther apart, they are pulled together. |
| `minRopeGap` | `0.4` | Smallest gap between two chained carts' edges, in blocks. Closer, they are pushed apart. |
| `stiffness` | `0.2` | How quickly chained carts fix their spacing (0 = never, 1 = within one tick). |
| `breakDistance` | `3.0` | A lead snaps when its ends get farther apart than this, in blocks. |
| `dropLeadOnBreak` | `true` | Drop the lead item when a lead breaks on its own. |

## Languages

The new rails are named in English, German, Spanish, French, Italian, Brazilian Portuguese, Dutch, Polish, Czech,
Russian, Ukrainian, Turkish, Simplified Chinese, Traditional Chinese, Japanese and Korean.

## Building from source

The build needs JDK 8:

```bash
./gradlew build
```

The mod jar is written to `build/libs/better_minecarts-0.1.jar`. `./gradlew runClient` starts a development client.

The build also runs the test suite. The tests run a real Minecraft world in memory, with vanilla carts and rails,
and check the physics on both the server and the client:
- trains on straights, U-turns and loops;
- collisions, walls and the speed limit;
- every vanilla cart type;
- furnace trains;
- players pushing carts.
