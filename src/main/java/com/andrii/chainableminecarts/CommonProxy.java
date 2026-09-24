package com.andrii.chainableminecarts;

import com.andrii.chainableminecarts.network.FurnaceStateMessage;
import com.andrii.chainableminecarts.network.SyncLinkMessage;

public class CommonProxy
{
    public void preInit()
    {
    }

    /** Applies a link sync packet. Only the client does anything with it. */
    public void handleSync(SyncLinkMessage message)
    {
    }

    /** Applies a furnace state packet. Only the client does anything with it. */
    public void handleFurnaceState(FurnaceStateMessage message)
    {
    }
}
