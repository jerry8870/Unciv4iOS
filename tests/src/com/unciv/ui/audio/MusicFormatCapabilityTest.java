package com.unciv.ui.audio;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.unciv.UncivGame;
import com.unciv.testing.GdxTestRunner;
import com.unciv.utils.PlatformCapabilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(GdxTestRunner.class)
public class MusicFormatCapabilityTest {
    private Audio previousAudio;
    private UncivGame previousGame;
    private Audio audio;
    private PlatformCapabilities capabilities;

    @Before public void setUp() {
        previousAudio = Gdx.audio;
        previousGame = UncivGame.Companion.isCurrentInitialized()
            ? UncivGame.Companion.getCurrent() : new UncivGame();
        audio = mock(Audio.class);
        Gdx.audio = audio;
        capabilities = mock(PlatformCapabilities.class);
        UncivGame game = mock(UncivGame.class);
        when(game.getPlatformCapabilities()).thenReturn(capabilities);
        UncivGame.Companion.setCurrent(game);
    }

    @After public void tearDown() {
        Gdx.audio = previousAudio;
        UncivGame.Companion.setCurrent(previousGame);
    }

    @Test public void unavailableOggNeverReachesNativeAudio() {
        MusicTrackController track = new MusicTrackController(0f, 1f);
        track.load(new FileHandle("unsupported.OGG"), null, null);
        assertEquals(MusicTrackController.State.Error, track.getState());
        verifyNoInteractions(audio);
    }

    @Test public void mp3StillReachesNativeAudio() {
        FileHandle file = new FileHandle("supported.mp3");
        when(audio.newMusic(file)).thenReturn(mock(Music.class));
        MusicTrackController track = new MusicTrackController(0f, 1f);
        track.load(file, null, null);
        assertEquals(MusicTrackController.State.Idle, track.getState());
        verify(audio).newMusic(file);
        track.clear();
    }

    @Test public void runtimeWithOggCapabilityReachesDecoder() {
        when(capabilities.getOggAudio()).thenReturn(true);
        FileHandle file = new FileHandle("supported.ogg");
        when(audio.newMusic(file)).thenReturn(mock(Music.class));
        MusicTrackController track = new MusicTrackController(0f, 1f);
        track.load(file, null, null);
        assertEquals(MusicTrackController.State.Idle, track.getState());
        verify(audio).newMusic(file);
        track.clear();
    }
}
