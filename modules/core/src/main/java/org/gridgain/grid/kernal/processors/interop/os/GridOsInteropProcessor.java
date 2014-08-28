/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.interop.os;

import org.gridgain.grid.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.interop.*;
import org.jetbrains.annotations.*;

/**
 * OS interop processor.
 */
public class GridOsInteropProcessor extends GridInteropProcessorAdapter {
    /**
     * Constructor.
     *
     * @param ctx Context.
     */
    public GridOsInteropProcessor(GridKernalContext ctx) {
        super(ctx);
    }

    @Override public GridInteropCache cache(@Nullable String name) throws GridException {
        throw new UnsupportedOperationException("Interop feature is not supported in OS edition.");
    }
}
