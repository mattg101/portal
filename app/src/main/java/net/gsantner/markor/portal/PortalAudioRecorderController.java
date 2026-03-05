package net.gsantner.markor.portal;

import android.media.MediaRecorder;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.File;

public class PortalAudioRecorderController {
    private MediaRecorder _recorder;
    private File _outFile;
    private boolean _recording;

    public void start(File outFile) throws Exception {
        stop(false);
        _outFile = outFile;

        final MediaRecorder recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder()
                : new MediaRecorder();

        recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(128000);
        recorder.setAudioSamplingRate(44100);
        recorder.setOutputFile(outFile.getAbsolutePath());
        recorder.prepare();

        recorder.start();
        _recorder = recorder;
        _recording = true;
    }

    public void stop(boolean keepFile) {
        if (_recorder != null) {
            try {
                if (_recording) {
                    _recorder.stop();
                }
            } catch (Exception ignored) {
                if (_outFile != null && _outFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    _outFile.delete();
                }
            }
            try {
                _recorder.release();
            } catch (Exception ignored) {
            }
            _recorder = null;
        }
        _recording = false;
        if (!keepFile && _outFile != null && _outFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            _outFile.delete();
        }
        if (!keepFile) {
            _outFile = null;
        }
    }

    public boolean isRecording() {
        return _recording;
    }

    @Nullable
    public File getOutputFile() {
        return _outFile;
    }

    @Nullable
    public File consumeOutputFile() {
        final File out = _outFile;
        _outFile = null;
        return out;
    }

}
