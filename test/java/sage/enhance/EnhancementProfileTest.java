/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Tests for the pure EPG-category to {@link EnhancementProfile} classifier.
 */
public class EnhancementProfileTest
{
  @Test
  public void testSportsWins()
  {
    assertEquals(EnhancementProfile.classify(new String[] { "Sports event" }),
        EnhancementProfile.SPORTS);
    assertEquals(EnhancementProfile.classify(new String[] { "Sports non-event", "Football" }),
        EnhancementProfile.SPORTS);
  }

  @Test
  public void testNewsAndTalk()
  {
    assertEquals(EnhancementProfile.classify(new String[] { "News" }),
        EnhancementProfile.NEWS_TALK);
    assertEquals(EnhancementProfile.classify(new String[] { "Talk" }),
        EnhancementProfile.NEWS_TALK);
    assertEquals(EnhancementProfile.classify(new String[] { "Talk show" }),
        EnhancementProfile.NEWS_TALK);
  }

  @Test
  public void testNature()
  {
    assertEquals(EnhancementProfile.classify(new String[] { "Nature" }),
        EnhancementProfile.NATURE);
    assertEquals(EnhancementProfile.classify(new String[] { "Wildlife" }),
        EnhancementProfile.NATURE);
  }

  @Test
  public void testExtendedVocabulary()
  {
    // Sports variants beyond the bare "Sports event"/"Sports non-event".
    assertEquals(EnhancementProfile.classify(new String[] { "Football" }),
        EnhancementProfile.SPORTS);
    assertEquals(EnhancementProfile.classify(new String[] { "Auto racing" }),
        EnhancementProfile.SPORTS);
    // News/talk variants.
    assertEquals(EnhancementProfile.classify(new String[] { "Interview" }),
        EnhancementProfile.NEWS_TALK);
    assertEquals(EnhancementProfile.classify(new String[] { "Public affairs" }),
        EnhancementProfile.NEWS_TALK);
    assertEquals(EnhancementProfile.classify(new String[] { "Weather" }),
        EnhancementProfile.NEWS_TALK);
    // High-detail documentary family folds into NATURE.
    assertEquals(EnhancementProfile.classify(new String[] { "Documentary" }),
        EnhancementProfile.NATURE);
    assertEquals(EnhancementProfile.classify(new String[] { "Science" }),
        EnhancementProfile.NATURE);
    assertEquals(EnhancementProfile.classify(new String[] { "Travel" }),
        EnhancementProfile.NATURE);
    // Unmatched scripted/entertainment stays GENERAL.
    assertEquals(EnhancementProfile.classify(new String[] { "Sitcom" }),
        EnhancementProfile.GENERAL);
    assertEquals(EnhancementProfile.classify(new String[] { "Reality" }),
        EnhancementProfile.GENERAL);
    assertEquals(EnhancementProfile.classify(new String[] { "Game show" }),
        EnhancementProfile.GENERAL);
  }

  @Test
  public void testFilm()
  {
    assertEquals(EnhancementProfile.classify(new String[] { "Movie" }),
        EnhancementProfile.FILM);
    assertEquals(EnhancementProfile.classify(new String[] { "Feature film" }),
        EnhancementProfile.FILM);
  }

  @Test
  public void testPrimaryCategoryOrderWins()
  {
    // The primary (first) category is the genre; a trailing sub-genre doesn't
    // override a news lead-in.
    assertEquals(EnhancementProfile.classify(new String[] { "News", "Sports" }),
        EnhancementProfile.NEWS_TALK);
  }

  @Test
  public void testUnknownAndEmptyFallBackToGeneral()
  {
    assertEquals(EnhancementProfile.classify(new String[] { "Sitcom" }),
        EnhancementProfile.GENERAL);
    assertEquals(EnhancementProfile.classify(new String[0]), EnhancementProfile.GENERAL);
    assertEquals(EnhancementProfile.classify(null), EnhancementProfile.GENERAL);
    assertEquals(EnhancementProfile.classify(new String[] { null, "" }),
        EnhancementProfile.GENERAL);
  }

  @Test
  public void testMotionClassMapping()
  {
    assertEquals(EnhancementProfile.SPORTS.motionClass(),
        EnhancementProfile.MotionClass.HIGH);
    assertEquals(EnhancementProfile.NATURE.motionClass(),
        EnhancementProfile.MotionClass.HIGH);
    assertEquals(EnhancementProfile.NEWS_TALK.motionClass(),
        EnhancementProfile.MotionClass.LOW);
    assertEquals(EnhancementProfile.FILM.motionClass(),
        EnhancementProfile.MotionClass.MEDIUM);
    assertEquals(EnhancementProfile.GENERAL.motionClass(),
        EnhancementProfile.MotionClass.MEDIUM);
  }

  @Test
  public void testMotionForFallsBackToFpsProxyWhenUnknown()
  {
    // Null or GENERAL profile => use the frame-rate proxy.
    assertEquals(MotionHint.motionFor(null, 60), EnhancementProfile.MotionClass.HIGH);
    assertEquals(MotionHint.motionFor(null, 30), EnhancementProfile.MotionClass.MEDIUM);
    assertEquals(MotionHint.motionFor(EnhancementProfile.GENERAL, 60),
        EnhancementProfile.MotionClass.HIGH);
    // A known genre wins regardless of fps.
    assertEquals(MotionHint.motionFor(EnhancementProfile.NEWS_TALK, 60),
        EnhancementProfile.MotionClass.LOW);
    assertEquals(MotionHint.motionFor(EnhancementProfile.SPORTS, 24),
        EnhancementProfile.MotionClass.HIGH);
  }
}
