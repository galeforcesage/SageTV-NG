/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance.spi.offline;

/**
 * An {@link AutoCloseable} handle returned when an {@link OfflineUpscaleProvider}
 * is registered. Closing it unregisters exactly the provider instance that was
 * registered under this id (identity-keyed), so one plugin cannot remove a
 * different provider that later claimed the same id.
 */
public final class OfflineUpscaleRegistration implements AutoCloseable
{
  private final OfflineUpscaleRegistry registry;
  private final OfflineUpscaleProvider provider;
  private final String id;

  OfflineUpscaleRegistration(OfflineUpscaleRegistry registry,
                             OfflineUpscaleProvider provider, String id)
  {
    this.registry = registry;
    this.provider = provider;
    this.id = id;
  }

  public String getId() { return id; }
  OfflineUpscaleProvider getProvider() { return provider; }

  @Override
  public void close()
  {
    registry.unregister(this);
  }
}
