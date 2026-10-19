/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage;

import org.testng.annotations.Test;

import java.util.Arrays;

import static org.testng.Assert.assertEquals;

public class SeekerDiskSpaceTest
{
  @Test
  public void testSharedFilesystemFreeSpaceIsCountedOnce()
  {
    Seeker.VideoStorage firstStore = new Seeker.VideoStorage(
        ".", 0, Seeker.VIDEO_DISKSPACE_USE_ALL);
    Seeker.VideoStorage secondStore = new Seeker.VideoStorage(
        ".", 0, Seeker.VIDEO_DISKSPACE_USE_ALL);
    Seeker.VideoDiskSpaceProbe probe = new Seeker.VideoDiskSpaceProbe()
    {
      public Object getFilesystemKey(Seeker.VideoStorage store)
      {
        return "shared-filesystem";
      }

      public long getPhysicalFree(Seeker.VideoStorage store)
      {
        return 6_000L;
      }

      public long getAvailable(Seeker.VideoStorage store)
      {
        return 6_000L;
      }
    };

    long combined = Seeker.calculateAvailableVideoDiskspace(
        Arrays.asList(firstStore, secondStore), probe);

    assertEquals(combined, 6_000L);
  }
}
