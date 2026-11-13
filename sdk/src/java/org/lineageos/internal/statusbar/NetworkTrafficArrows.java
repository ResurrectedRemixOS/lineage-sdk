/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.internal.statusbar;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

public final class NetworkTrafficArrows {

    public static final int COUNT = 8;

    public static final float WIDTH = 14f;
    public static final float HEIGHT = 16f;

    private static final int FILL = 0;
    private static final int STROKE = 1;
    private static final int FILL_AND_STROKE = 2;

    private static final Set[] SETS = {
        new Set("M 4 1 L 1 4 H 3 V 9 H 5 V 4 H 7 Z",
                "M 10 15 L 13 12 H 11 V 7 H 9 V 12 H 7 Z",
                FILL, 0f, 0f, 6f, 10f),
        new Set("M 4 3 L 7.4 8.2 L 0.6 8.2 Z",
                "M 10 13 L 13.4 7.8 L 6.6 7.8 Z",
                FILL, 0f, 0.6f, 5.4f, 10f),
        new Set("M 1.2 7.2 L 4 4.4 L 6.8 7.2",
                "M 7.2 8.8 L 10 11.6 L 12.8 8.8",
                STROKE, 1.7f, 0.8f, 5.2f, 10f),
        new Set("M 4 9.5 V 2 M 1.6 4.4 L 4 2 L 6.4 4.4",
                "M 10 6.5 V 14 M 7.6 11.6 L 10 14 L 12.4 11.6",
                STROKE, 1.4f, 0.75f, 5.25f, 10f),
        new Set("M 4 1.8 L 6.6 5 H 5 V 9.2 H 3 V 5 H 1.4 Z",
                "M 10 14.2 L 12.6 11 H 11 V 6.8 H 9 V 11 H 7.4 Z",
                FILL_AND_STROKE, 1.2f, 0.5f, 5.5f, 10f),
        new Set("O 4 5 2.2",
                "O 10 11 2.2",
                FILL, 0f, 0f, 6f, 10f),
        new Set("M 4 8 V 1.8 M 1.9 3.9 L 4 1.8 L 6.1 3.9 M 1.4 10.2 H 6.6",
                "M 10 6.2 V 12.4 M 7.9 10.3 L 10 12.4 L 12.1 10.3 M 7.4 14.6 H 12.6",
                STROKE, 1.3f, 1f, 5.4f, 10f),
        new Set("M 4 1.5 L 7 5 H 4.8 V 14.5 H 3.2 V 5 H 1 Z",
                "M 10 14.5 L 13 11 H 10.8 V 1.5 H 9.2 V 11 H 7 Z",
                FILL, 0f, 0.5f, 0.5f, 15f),
    };

    private NetworkTrafficArrows() {}

    public static Set get(int index) {
        return SETS[index >= 0 && index < COUNT ? index : 0];
    }

    public static Drawable pairDrawable(int index, int color) {
        return new PairDrawable(get(index), color);
    }

    public static final class Set {
        private final Path mUp;
        private final Path mDown;
        private final int mStyle;
        private final float mStrokeWidth;
        public final RectF upBox;
        public final RectF downBox;

        private Set(String up, String down, int style, float strokeWidth,
                float upTop, float downTop, float height) {
            mUp = parse(up);
            mDown = parse(down);
            mStyle = style;
            mStrokeWidth = strokeWidth;
            upBox = new RectF(0f, upTop, 8f, upTop + height);
            downBox = new RectF(6f, downTop, 14f, downTop + height);
        }

        public void draw(Canvas canvas, boolean up, Paint paint) {
            paint.setStyle(mStyle == FILL ? Paint.Style.FILL
                    : mStyle == STROKE ? Paint.Style.STROKE : Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(mStrokeWidth);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(up ? mUp : mDown, paint);
        }
    }

    private static Path parse(String data) {
        final Path path = new Path();
        final String[] t = data.trim().split("\\s+");
        float x = 0f, y = 0f;
        int i = 0;
        while (i < t.length) {
            switch (t[i++]) {
                case "M":
                    x = Float.parseFloat(t[i++]);
                    y = Float.parseFloat(t[i++]);
                    path.moveTo(x, y);
                    break;
                case "L":
                    x = Float.parseFloat(t[i++]);
                    y = Float.parseFloat(t[i++]);
                    path.lineTo(x, y);
                    break;
                case "H":
                    x = Float.parseFloat(t[i++]);
                    path.lineTo(x, y);
                    break;
                case "V":
                    y = Float.parseFloat(t[i++]);
                    path.lineTo(x, y);
                    break;
                case "Z":
                    path.close();
                    break;
                case "O":
                    path.addCircle(Float.parseFloat(t[i++]), Float.parseFloat(t[i++]),
                            Float.parseFloat(t[i++]), Path.Direction.CW);
                    break;
                default:
                    throw new IllegalArgumentException("Bad path: " + data);
            }
        }
        return path;
    }

    private static final class PairDrawable extends Drawable {
        private final Set mSet;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PairDrawable(Set set, int color) {
            mSet = set;
            mPaint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect b = getBounds();
            final float scale = Math.min(b.width() / WIDTH, b.height() / HEIGHT);
            canvas.save();
            canvas.translate(b.exactCenterX() - WIDTH * scale / 2f,
                    b.exactCenterY() - HEIGHT * scale / 2f);
            canvas.scale(scale, scale);
            mSet.draw(canvas, true, mPaint);
            mSet.draw(canvas, false, mPaint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            mPaint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public void setTint(int tint) {
            mPaint.setColor(tint);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
