package io.trino.spi;

import io.trino.spi.security.GroupProvider;
import io.trino.spi.security.GroupProviderFactory;

import java.util.Map;

public class SteerDGroupProviderFactory
        implements GroupProviderFactory
{
    @Override
    public String getName()
    {
        return "steerd-group-provider";
    }

    @Override
    public GroupProvider create(Map<String, String> config)
    {
        return new SteerDGroupProvider();
    }
}
