package io.trino.memory;

import io.trino.spi.memory.ClusterMemoryPoolManager;
import io.trino.spi.memory.MemoryPoolId;
import io.trino.spi.memory.MemoryPoolInfo;

import javax.inject.Inject;

import java.util.function.Consumer;

public class SteerDPrestoClusterMemoryManager
        implements ClusterMemoryPoolManager
{
    @Override
    public void addChangeListener(MemoryPoolId poolId, Consumer<MemoryPoolInfo> listener)
    {
    }

    @Inject
    public SteerDPrestoClusterMemoryManager() {}
}
