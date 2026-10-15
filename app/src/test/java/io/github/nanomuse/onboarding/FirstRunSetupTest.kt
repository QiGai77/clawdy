package io.github.nanomuse.onboarding

import io.github.nanomuse.ui.onboarding.FirstRunSetup
import io.github.nanomuse.ui.onboarding.FirstRunSetup.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunSetupTest {

    @Test
    fun `a fresh install is walked through the setup until Start`() {
        assertTrue(FirstRunSetup.needed(signedIn = false, hasProviders = false, hasSessions = false, done = false))
        // Signed in (the relay is a provider), models picked, but Start not tapped yet: still the setup.
        assertTrue(FirstRunSetup.needed(signedIn = true, hasProviders = true, hasSessions = false, done = false))
        assertFalse(FirstRunSetup.needed(signedIn = true, hasProviders = true, hasSessions = false, done = true))
    }

    @Test
    fun `an existing install with conversations never sees the setup while signed in`() {
        assertFalse(FirstRunSetup.needed(signedIn = true, hasProviders = true, hasSessions = true, done = false))
        assertFalse(FirstRunSetup.needed(signedIn = true, hasProviders = true, hasSessions = true, done = true))
    }

    @Test
    fun `signed out with a key of one's own, the chat stays - the sign-in is an invitation`() {
        assertFalse(FirstRunSetup.needed(signedIn = false, hasProviders = true, hasSessions = true, done = true))
        // The account alone is enough too: the relay is a provider.
        assertFalse(FirstRunSetup.needed(signedIn = true, hasProviders = false, hasSessions = true, done = true))
        // Nothing that could answer: the setup comes back.
        assertTrue(FirstRunSetup.needed(signedIn = false, hasProviders = false, hasSessions = true, done = true))
    }

    @Test
    fun `signed out with a key of one's own, the account pages are skipped`() {
        // The welcome stays only while there is nothing at all.
        assertEquals(Stage.WELCOME, FirstRunSetup.stage(signedIn = false, hasGroups = false, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true, hasProviders = false))
        // The key is there, its models are not grouped yet: straight to the models page, no password, no source question.
        assertEquals(Stage.MODELS, FirstRunSetup.stage(signedIn = false, hasGroups = false, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true, fresh = true, passwordAnswered = false, hasProviders = true))
        assertEquals(Stage.HANDS, FirstRunSetup.stage(signedIn = false, hasGroups = true, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true, hasProviders = true))
        assertEquals(Stage.MEET, FirstRunSetup.stage(signedIn = false, hasGroups = true, sourceChosen = false, modelsSkipped = false, handsSeen = true, handsPossible = true, hasProviders = true))
    }

    @Test
    fun `the pages come in order - account, model source, models, hands, meet`() {
        assertEquals(Stage.WELCOME, FirstRunSetup.stage(signedIn = false, hasGroups = false, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true))
        assertEquals(Stage.SOURCE, FirstRunSetup.stage(signedIn = true, hasGroups = true, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true))
        // Own key added but its models not grouped yet.
        assertEquals(Stage.MODELS, FirstRunSetup.stage(signedIn = true, hasGroups = false, sourceChosen = true, modelsSkipped = false, handsSeen = false, handsPossible = true))
        assertEquals(Stage.HANDS, FirstRunSetup.stage(signedIn = true, hasGroups = true, sourceChosen = true, modelsSkipped = false, handsSeen = false, handsPossible = true))
        assertEquals(Stage.MEET, FirstRunSetup.stage(signedIn = true, hasGroups = true, sourceChosen = true, modelsSkipped = false, handsSeen = true, handsPossible = true))
        // A phone too old for Hands skips that page.
        assertEquals(Stage.MEET, FirstRunSetup.stage(signedIn = true, hasGroups = true, sourceChosen = true, modelsSkipped = false, handsSeen = false, handsPossible = false))
    }

    @Test
    fun `a sign-in that created the account is followed by the password page`() {
        // owed once; answered (or skipped) once
        assertEquals(Stage.PASSWORD, FirstRunSetup.stage(signedIn = true, hasGroups = true, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true, fresh = true, passwordAnswered = false))
        assertEquals(Stage.SOURCE, FirstRunSetup.stage(signedIn = true, hasGroups = true, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true, fresh = true, passwordAnswered = true))
        // an existing account signing in again does not see it
        assertEquals(Stage.SOURCE, FirstRunSetup.stage(signedIn = true, hasGroups = true, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true, fresh = false, passwordAnswered = false))
        // never before the sign-in itself
        assertEquals(Stage.WELCOME, FirstRunSetup.stage(signedIn = false, hasGroups = true, sourceChosen = false, modelsSkipped = false, handsSeen = false, handsPossible = true, fresh = true, passwordAnswered = false))
        assertEquals(0, FirstRunSetup.dot(Stage.PASSWORD))
    }

    @Test
    fun `the three dots - the model pages fold into the first`() {
        assertEquals(0, FirstRunSetup.dot(Stage.WELCOME))
        assertEquals(0, FirstRunSetup.dot(Stage.SOURCE))
        assertEquals(0, FirstRunSetup.dot(Stage.MODELS))
        assertEquals(1, FirstRunSetup.dot(Stage.HANDS))
        assertEquals(2, FirstRunSetup.dot(Stage.MEET))
    }
}
