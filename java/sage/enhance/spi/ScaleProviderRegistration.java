/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance.spi;

/**
 * An {@link AutoCloseable} handle returned when a provider is registered.
 *
 * <p>Closing the handle unregisters exactly the provider instance that was
 * registered under this id. Because unregistration is keyed on instance identity
 * (not just the id string), one plugin cannot accidentally remove a different
 * provider that later claimed the same id.
 */
public final class ScaleProviderRegistration implements AutoCloseable
{
  private final ScaleProviderRegistry registry;
  private final ScaleProvider provider;
  private final String id;

  ScaleProviderRegistration(ScaleProviderRegistry registry, ScaleProvider provider, String id)
  {
    this.registry = registry;
    this.provider = provider;
    this.id = id;
  }

  public String getId() { return id; }
  ScaleProvider getProvider() { return provider; }

  @Override
  public void close()
  {
    registry.unregister(this);
  }
}
