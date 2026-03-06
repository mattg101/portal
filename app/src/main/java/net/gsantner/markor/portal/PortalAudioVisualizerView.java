package net.gsantner.markor.portal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.gsantner.markor.R;

public class PortalAudioVisualizerView extends View {
    private final Paint _barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private byte[] _fft;

    public PortalAudioVisualizerView(Context context) {
        super(context);
        init();
    }

    public PortalAudioVisualizerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        _barPaint.setColor(ContextCompat.getColor(getContext(), R.color.accent));
        _barPaint.setStrokeWidth(18f);
        _barPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void updateFft(@Nullable byte[] fft) {
        _fft = fft;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int width = getWidth();
        final int height = getHeight();
        if (_fft == null || _fft.length < 4 || width <= 0 || height <= 0) {
            final int count = 12;
            final float spacing = width / (float) (count + 1);
            for (int i = 0; i < count; i++) {
                final float x = spacing * (i + 1);
                canvas.drawLine(x, height * 0.68f, x, height * 0.92f, _barPaint);
            }
            return;
        }

        final int bars = Math.min(18, (_fft.length / 2) - 1);
        final float spacing = width / (float) (bars + 1);
        for (int i = 0; i < bars; i++) {
            final int rfk = _fft[2 * i];
            final int ifk = _fft[2 * i + 1];
            final float magnitude = (float) Math.min(1.0, Math.hypot(rfk, ifk) / 180.0);
            final float barHeight = (height * 0.18f) + (magnitude * height * 0.64f);
            final float x = spacing * (i + 1);
            canvas.drawLine(x, height - 12f, x, height - barHeight, _barPaint);
        }
    }
}
