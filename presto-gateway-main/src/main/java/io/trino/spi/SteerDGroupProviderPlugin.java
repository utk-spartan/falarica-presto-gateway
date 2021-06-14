package io.trino.spi;

import com.google.common.collect.ImmutableList;
import io.trino.spi.security.GroupProviderFactory;

public class SteerDGroupProviderPlugin
        implements Plugin
{
    @Override
    public Iterable<GroupProviderFactory> getGroupProviderFactories()
    {
        return ImmutableList.of(
                new SteerDGroupProviderFactory());
    }
}
