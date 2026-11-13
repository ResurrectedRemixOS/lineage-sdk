/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.internal.statusbar;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.widget.TextView;

import lineageos.providers.LineageSettings;

import org.lineageos.platform.internal.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Stream;

public class NetworkTraffic extends TextView {
    private static final String TAG = "NetworkTraffic";

    private static final boolean DEBUG = false;

    // This must match the interface prefix in Connectivity's clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    private static final int MODE_DISABLED = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;
    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 3;

    private static final int POSITION_START = 0;
    private static final int POSITION_CENTER = 1;
    private static final int POSITION_END = 2;
    public static final int POSITION_SHADE_HEADER = 3;

    private static final int MESSAGE_TYPE_PERIODIC_REFRESH = 0;
    private static final int MESSAGE_TYPE_UPDATE_VIEW = 1;
    private static final int MESSAGE_TYPE_ADD_NETWORK = 2;
    private static final int MESSAGE_TYPE_REMOVE_NETWORK = 3;

    private static final int UNITS_KILOBITS = 0;
    private static final int UNITS_MEGABITS = 1;
    private static final int UNITS_KILOBYTES = 2;
    private static final int UNITS_MEGABYTES = 3;
    private static final int UNITS_AUTOBYTES = 4;

    private static final int SHOW_UNITS_OFF = 0;
    private static final int SHOW_UNITS_ON = 1;
    private static final int SHOW_UNITS_COMPACT = 2;

    private static final int STYLE_CLASSIC = 0;
    private static final int STYLE_INLINE = 1;
    private static final int STYLE_PILL = 2;
    private static final int STYLE_DOMINANT = 3;
    private static final int STYLE_METER = 4;
    private static final int STYLE_SPARKLINE = 5;
    private static final int STYLE_READOUT = 6;
    private static final int STYLE_RING = 7;

    private static final int COLOR_STATUS_BAR = 0;
    private static final int COLOR_ACCENT = 1;
    private static final int COLOR_SPEED = 2;

    // Thresholds themselves are always defined in kbps
    private static final long AUTOHIDE_THRESHOLD_KILOBITS  = 10;
    private static final long AUTOHIDE_THRESHOLD_MEGABITS  = 100;
    private static final long AUTOHIDE_THRESHOLD_KILOBYTES = 8;
    private static final long AUTOHIDE_THRESHOLD_MEGABYTES = 80;

    private static final long ACTIVE_KBPS = 8;
    private static final float IDLE_ALPHA = 0.32f;
    private static final float TRACK_ALPHA = 0.28f;
    private static final int HISTORY = 24;
    private static final float[] METER_BARS = {3.5f, 5.5f, 7.5f, 9.5f, 11.5f};
    private static final double FULL_SCALE = Math.log10(100001);

    private final int mTextSizeSingle;
    private final int mTextSizeMulti;
    private final float mDensity;
    private final float mScaledDensity;
    private final Handler mTrafficHandler;
    private final SettingsObserver mObserver;

    private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics mMetrics = new Paint.FontMetrics();
    private final RectF mRect = new RectF();
    private final Path mSparkLine = new Path();
    private final Path mSparkArea = new Path();
    private final long[] mTxHistory = new long[HISTORY];
    private final long[] mRxHistory = new long[HISTORY];
    private final Typeface mRegular;
    private final Typeface mMedium;
    private final Typeface mBold;

    private int mMode = MODE_DISABLED;
    private int mPosition = POSITION_CENTER;
    private int mViewPosition = -1;
    private boolean mNetworkTrafficIsVisible;
    private long mTxKbps;
    private long mRxKbps;
    private long mLastTxBytes;
    private long mLastRxBytes;
    private long mLastUpdateTime;
    private boolean mAutoHide;
    private boolean mHideArrows;
    private long mAutoHideThreshold;
    private int mUnits;
    private int mShowUnits;
    private int mIconTint = Color.WHITE;
    private float mDarkIntensity;

    private int mStyle = STYLE_CLASSIC;
    private NetworkTrafficArrows.Set mArrows = NetworkTrafficArrows.get(0);
    private float mSize = 1f;
    private int mColorMode = COLOR_STATUS_BAR;
    private boolean mLiveArrows = true;
    private int mRefreshMs = 2000;
    private boolean mMobileOnly;
    private volatile boolean mOnMobile;
    private boolean mShowUp;
    private boolean mShowDown;
    private int mHistoryPos;
    private int mContentWidth;
    private int mContentHeight;
    private float mRenderHeight;

    // Network tracking related variables
    private ConnectivityManager mConnectivityManager;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private ConnectivityManager.NetworkCallback mDefaultNetworkCallback;

    private final HashMap<Network, LinkProperties> mLinkPropertiesMap = new HashMap<>();
    // Used to indicate that the set of sources contributing
    // to current stats have changed.
    private boolean mNetworksChanged = true;

    private LineageStatusBarItem.Manager mManager;

    public NetworkTraffic(Context context) {
        this(context, null);
    }

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources resources = getResources();
        mTextSizeSingle = resources.getDimensionPixelSize(R.dimen.net_traffic_single_text_size);
        mTextSizeMulti = resources.getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);
        final DisplayMetrics dm = resources.getDisplayMetrics();
        mDensity = dm.density;
        mScaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, dm);

        final Typeface base = getTypeface() != null ? getTypeface() : Typeface.DEFAULT;
        mRegular = base;
        mMedium = Typeface.create(base, 600, false);
        mBold = Typeface.create(base, 700, false);
        mTextPaint.setFontFeatureSettings("tnum");

        mNetworkTrafficIsVisible = false;

        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mTrafficHandler = new Handler(mContext.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MESSAGE_TYPE_PERIODIC_REFRESH:
                        recalculateStats();
                        displayStatsAndReschedule();
                        break;

                    case MESSAGE_TYPE_UPDATE_VIEW:
                        displayStatsAndReschedule();
                        break;

                    case MESSAGE_TYPE_ADD_NETWORK:
                        final LinkPropertiesHolder lph = (LinkPropertiesHolder) msg.obj;
                        mLinkPropertiesMap.put(lph.getNetwork(), lph.getLinkProperties());
                        mNetworksChanged = true;
                        break;

                    case MESSAGE_TYPE_REMOVE_NETWORK:
                        mLinkPropertiesMap.remove((Network) msg.obj);
                        mNetworksChanged = true;
                        break;
                }
            }

            private void recalculateStats() {
                final long now = SystemClock.elapsedRealtime();
                final long timeDelta = now - mLastUpdateTime; /* ms */
                if (timeDelta < mRefreshMs * 0.95f) {
                    return;
                }
                // Sum tx and rx bytes from all sources of interest
                long txBytes = 0;
                long rxBytes = 0;
                // Add interface stats, including stats from Clat's IPv4 interface
                // (for applicable IPv6 networks). Stats are 0 if it doesn't exist.
                final String[] ifaces = mLinkPropertiesMap.values().stream()
                        .map(link -> link.getInterfaceName()).filter(iface -> iface != null)
                        .flatMap(iface -> Stream.of(iface, CLAT_PREFIX + iface))
                        .toArray(String[]::new);
                for (String iface : ifaces) {
                    final long ifaceTxBytes = TrafficStats.getTxBytes(iface);
                    final long ifaceRxBytes = TrafficStats.getRxBytes(iface);
                    if (DEBUG) {
                        Log.d(TAG, "adding stats from interface " + iface
                                + " txbytes " + ifaceTxBytes + " rxbytes " + ifaceRxBytes);
                    }
                    txBytes += ifaceTxBytes;
                    rxBytes += ifaceRxBytes;
                }

                final long txBytesDelta = txBytes - mLastTxBytes;
                final long rxBytesDelta = rxBytes - mLastRxBytes;

                if (!mNetworksChanged && timeDelta > 0 && txBytesDelta >= 0 && rxBytesDelta >= 0) {
                    mTxKbps = (long) (txBytesDelta * 8f / 1000f / (timeDelta / 1000f));
                    mRxKbps = (long) (rxBytesDelta * 8f / 1000f / (timeDelta / 1000f));
                } else if (mNetworksChanged) {
                    mTxKbps = 0;
                    mRxKbps = 0;
                    mNetworksChanged = false;
                }
                mLastTxBytes = txBytes;
                mLastRxBytes = rxBytes;
                mLastUpdateTime = now;

                mTxHistory[mHistoryPos] = mTxKbps;
                mRxHistory[mHistoryPos] = mRxKbps;
                mHistoryPos = (mHistoryPos + 1) % HISTORY;
            }

            private void displayStatsAndReschedule() {
                final boolean enabled = mMode != MODE_DISABLED
                        && (mPosition == mViewPosition
                                || mViewPosition == POSITION_SHADE_HEADER)
                        && isConnectionAvailable() && (!mMobileOnly || mOnMobile);
                mShowUp = mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
                mShowDown = mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
                final boolean shouldHide = mAutoHide
                        && (!mShowUp || mTxKbps < mAutoHideThreshold)
                        && (!mShowDown || mRxKbps < mAutoHideThreshold);

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "view=" + mViewPosition + " position=" + mPosition
                            + " mode=" + mMode + " connected=" + isConnectionAvailable()
                            + " mobileOnly=" + mMobileOnly + " onMobile=" + mOnMobile
                            + " hide=" + shouldHide + " visible=" + mNetworkTrafficIsVisible
                            + " user=" + mContext.getUserId());
                }
                if (!enabled || shouldHide) {
                    setVisibility(GONE);
                } else {
                    updateContent();
                    setVisibility(VISIBLE);
                }

                // Schedule periodic refresh
                mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
                if (enabled && mNetworkTrafficIsVisible) {
                    mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH,
                            mRefreshMs);
                }
            }
        };
        mObserver = new SettingsObserver(mTrafficHandler);
        updateSettings();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int w = mContentWidth + getPaddingLeft() + getPaddingRight();
        final int h = mContentHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mContentWidth == 0) {
            return;
        }
        final float innerWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        final float innerHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        render(canvas, getPaddingLeft() + (innerWidth - mContentWidth) / 2f,
                getPaddingTop() + innerHeight / 2f);
    }

    private void updateContent() {
        final int w = (int) Math.ceil(render(null, 0f, 0f));
        final int h = (int) Math.ceil(mRenderHeight);
        setContentDescription(describe());
        if (w != mContentWidth || h != mContentHeight) {
            mContentWidth = w;
            mContentHeight = h;
            requestLayout();
        }
        invalidate();
    }

    private float render(Canvas c, float x, float cy) {
        final int color = contentColor();
        mTextPaint.setColor(color);
        switch (mStyle) {
            case STYLE_INLINE:
                return renderInline(c, x, cy, color, sp(11.5f), dp(0.8f), dp(6f));
            case STYLE_PILL:
                return renderPill(c, x, cy, color);
            case STYLE_DOMINANT:
                return renderDominant(c, x, cy, color);
            case STYLE_METER:
                return renderMeter(c, x, cy, color);
            case STYLE_SPARKLINE:
                return renderSparkline(c, x, cy, color);
            case STYLE_READOUT:
                return renderReadout(c, x, cy, color);
            case STYLE_RING:
                return renderRing(c, x, cy, color);
            default:
                return renderClassic(c, x, cy, color);
        }
    }

    private float renderClassic(Canvas c, float x, float cy, int color) {
        final boolean two = mShowUp && mShowDown;
        textStyle((two ? mTextSizeMulti : mTextSizeSingle) * mSize, mRegular);
        final String up = mShowUp ? classicText(mTxKbps) : "";
        final String down = mShowDown ? classicText(mRxKbps) : "";
        final float column = Math.max(mTextPaint.measureText(up), mTextPaint.measureText(down));
        final float line = mTextPaint.getFontSpacing();
        if (c != null) {
            if (two) {
                c.drawText(up, x + column - mTextPaint.measureText(up),
                        baseline(cy - line / 2f), mTextPaint);
                c.drawText(down, x + column - mTextPaint.measureText(down),
                        baseline(cy + line / 2f), mTextPaint);
            } else {
                final String text = mShowUp ? up : down;
                c.drawText(text, x + column - mTextPaint.measureText(text), baseline(cy),
                        mTextPaint);
            }
        }
        float w = column;
        float h = two ? line * 2f : line;
        if (!mHideArrows) {
            final float u = dp(1f);
            w += dp(1.5f);
            if (c != null) {
                c.save();
                c.translate(x + w, cy - NetworkTrafficArrows.HEIGHT * u / 2f);
                c.scale(u, u);
                if (mShowUp) {
                    mPaint.setColor(color);
                    mPaint.setAlpha(arrowAlpha(color, mTxKbps));
                    mArrows.draw(c, true, mPaint);
                }
                if (mShowDown) {
                    mPaint.setColor(color);
                    mPaint.setAlpha(arrowAlpha(color, mRxKbps));
                    mArrows.draw(c, false, mPaint);
                }
                c.restore();
            }
            w += NetworkTrafficArrows.WIDTH * u;
            h = Math.max(h, NetworkTrafficArrows.HEIGHT * u);
        }
        mRenderHeight = h;
        return w;
    }

    private float renderInline(Canvas c, float x, float cy, int color, float textSize,
            float unit, float gap) {
        textStyle(textSize, mMedium);
        float w = 0f;
        if (mShowUp) {
            w += item(c, true, x + w, cy, unit, color, mTxKbps, compactText(mTxKbps));
        }
        if (mShowDown) {
            if (mShowUp) {
                w += gap;
            }
            w += item(c, false, x + w, cy, unit, color, mRxKbps, compactText(mRxKbps));
        }
        mRenderHeight = Math.max(lineHeight(), glyphHeight(unit));
        return w;
    }

    private float renderPill(Canvas c, float x, float cy, int color) {
        final float pad = dp(7f);
        final float h = dp(19f);
        final float textSize = sp(10.5f);
        final float unit = dp(0.7f);
        final float gap = dp(5f);
        if (c != null) {
            final float w = renderInline(null, 0f, 0f, color, textSize, unit, gap) + pad * 2f;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(pillColor(color));
            mRect.set(x, cy - h / 2f, x + w, cy + h / 2f);
            c.drawRoundRect(mRect, h / 2f, h / 2f, mPaint);
        }
        final float inner = renderInline(c, x + pad, cy, color, textSize, unit, gap);
        mRenderHeight = Math.max(h, mRenderHeight);
        return inner + pad * 2f;
    }

    private float renderDominant(Canvas c, float x, float cy, int color) {
        final boolean up = dominantUp();
        final long kbps = up ? mTxKbps : mRxKbps;
        final float unit = dp(0.85f);
        textStyle(sp(12.5f), mMedium);
        float w = glyph(c, up, x, cy, unit, color, kbps);
        if (w > 0f) {
            w += dp(3f);
        }
        final String text = fullText(kbps);
        if (c != null) {
            c.drawText(text, x + w, baseline(cy), mTextPaint);
        }
        w += mTextPaint.measureText(text);
        mRenderHeight = Math.max(lineHeight(), glyphHeight(unit));
        return w;
    }

    private float renderMeter(Canvas c, float x, float cy, int color) {
        float w = 0f;
        if (mShowUp) {
            w += meter(c, true, x + w, cy, color, mTxKbps);
        }
        if (mShowDown) {
            if (mShowUp) {
                w += dp(6f);
            }
            w += meter(c, false, x + w, cy, color, mRxKbps);
        }
        mRenderHeight = Math.max(dp(METER_BARS[METER_BARS.length - 1]), glyphHeight(dp(0.7f)));
        return w;
    }

    private float meter(Canvas c, boolean up, float x, float cy, int color, long kbps) {
        final float bar = dp(2.4f);
        final float gap = dp(1.2f);
        final float radius = dp(0.8f);
        final float bottom = cy + dp(METER_BARS[METER_BARS.length - 1]) / 2f;
        final int lit = kbps < ACTIVE_KBPS ? 0
                : Math.max(1, (int) Math.ceil(fraction(kbps) * METER_BARS.length));
        float w = glyph(c, up, x, cy, dp(0.7f), color, kbps);
        if (w > 0f) {
            w += gap;
        }
        for (int i = 0; i < METER_BARS.length; i++) {
            if (c != null) {
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(color);
                if (i >= lit) {
                    mPaint.setAlpha((int) (Color.alpha(color) * TRACK_ALPHA));
                }
                mRect.set(x + w, bottom - dp(METER_BARS[i]), x + w + bar, bottom);
                c.drawRoundRect(mRect, radius, radius, mPaint);
            }
            w += bar;
            if (i < METER_BARS.length - 1) {
                w += gap;
            }
        }
        return w;
    }

    private float renderSparkline(Canvas c, float x, float cy, int color) {
        final boolean up = dominantUp();
        final long kbps = up ? mTxKbps : mRxKbps;
        final float gw = dp(30f);
        final float gh = dp(14f);
        if (c != null) {
            drawSparkline(c, up ? mTxHistory : mRxHistory, x, cy - gh / 2f, gw, gh, color);
        }
        textStyle(sp(11.5f), mMedium);
        float w = gw + dp(5f);
        w += item(c, up, x + w, cy, dp(0.7f), color, kbps, compactText(kbps));
        mRenderHeight = Math.max(gh, Math.max(lineHeight(), glyphHeight(dp(0.7f))));
        return w;
    }

    private void drawSparkline(Canvas c, long[] history, float x, float top, float w, float h,
            int color) {
        long max = 100;
        for (long v : history) {
            max = Math.max(max, v);
        }
        mSparkLine.reset();
        for (int i = 0; i < HISTORY; i++) {
            final long v = history[(mHistoryPos + i) % HISTORY];
            final float px = x + w * i / (HISTORY - 1);
            final float py = top + h * 0.95f - h * 0.9f * v / max;
            if (i == 0) {
                mSparkLine.moveTo(px, py);
            } else {
                mSparkLine.lineTo(px, py);
            }
        }
        mSparkArea.set(mSparkLine);
        mSparkArea.lineTo(x + w, top + h);
        mSparkArea.lineTo(x, top + h);
        mSparkArea.close();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
        mPaint.setAlpha((int) (Color.alpha(color) * TRACK_ALPHA));
        c.drawPath(mSparkArea, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setColor(color);
        mPaint.setStrokeWidth(dp(1.3f));
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(mSparkLine, mPaint);
    }

    private float renderReadout(Canvas c, float x, float cy, int color) {
        final boolean up = dominantUp();
        final long kbps = up ? mTxKbps : mRxKbps;
        final String[] speed = speed(kbps);
        final String unit = mShowUnits == SHOW_UNITS_OFF ? "" : speed[1];
        final float glyphUnit = dp(0.55f);
        final float glyphWidth = mHideArrows ? 0f : mArrows.upBox.width() * glyphUnit;
        final float glyphGap = glyphWidth > 0f && !unit.isEmpty() ? dp(1.5f) : 0f;

        textStyle(sp(7f), mMedium);
        mTextPaint.setLetterSpacing(0.04f);
        final float unitHeight = mTextPaint.getTextSize() * 0.72f;
        final float unitWidth = glyphWidth + glyphGap + mTextPaint.measureText(unit);

        textStyle(sp(14.5f), mBold);
        final float valueHeight = mTextPaint.getTextSize() * 0.72f;
        final float valueWidth = mTextPaint.measureText(speed[0]);

        final float w = Math.max(valueWidth, unitWidth);
        final float gap = dp(2.5f);
        final float h = valueHeight + gap + unitHeight;
        final float top = cy - h / 2f;
        if (c != null) {
            c.drawText(speed[0], x + w - valueWidth, top + valueHeight, mTextPaint);
            textStyle(sp(7f), mMedium);
            mTextPaint.setLetterSpacing(0.04f);
            mTextPaint.setAlpha((int) (Color.alpha(color) * 0.85f));
            final float unitBaseline = top + valueHeight + gap + unitHeight;
            float ux = x + w - unitWidth;
            ux += glyph(c, up, ux, unitBaseline - unitHeight / 2f, glyphUnit, color, kbps);
            c.drawText(unit, ux + glyphGap, unitBaseline, mTextPaint);
            mTextPaint.setColor(color);
        }
        mRenderHeight = h + dp(2f);
        return w;
    }

    private float renderRing(Canvas c, float x, float cy, int color) {
        final boolean up = dominantUp();
        final long kbps = up ? mTxKbps : mRxKbps;
        final float d = dp(15f);
        final float r = d * 7f / 18f;
        if (c != null) {
            final float cx = x + d / 2f;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(d * 2.6f / 18f);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setColor(color);
            mPaint.setAlpha((int) (Color.alpha(color) * TRACK_ALPHA));
            c.drawCircle(cx, cy, r, mPaint);
            final float sweep = (float) fraction(kbps) * 360f;
            if (sweep > 0f) {
                mPaint.setColor(color);
                mRect.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(mRect, -90f, sweep, false, mPaint);
            }
        }
        textStyle(sp(11.5f), mMedium);
        float w = d + dp(4f);
        w += item(c, up, x + w, cy, dp(0.7f), color, kbps, compactText(kbps));
        mRenderHeight = Math.max(d, Math.max(lineHeight(), glyphHeight(dp(0.7f))));
        return w;
    }

    private float item(Canvas c, boolean up, float x, float cy, float unit, int color, long kbps,
            String text) {
        float w = glyph(c, up, x, cy, unit, color, kbps);
        if (w > 0f) {
            w += dp(1.5f);
        }
        if (c != null) {
            c.drawText(text, x + w, baseline(cy), mTextPaint);
        }
        return w + mTextPaint.measureText(text);
    }

    private float glyph(Canvas c, boolean up, float x, float cy, float unit, int color,
            long kbps) {
        if (mHideArrows) {
            return 0f;
        }
        final RectF box = up ? mArrows.upBox : mArrows.downBox;
        if (c != null) {
            c.save();
            c.translate(x - box.left * unit, cy - box.centerY() * unit);
            c.scale(unit, unit);
            mPaint.setColor(color);
            mPaint.setAlpha(arrowAlpha(color, kbps));
            mArrows.draw(c, up, mPaint);
            c.restore();
        }
        return box.width() * unit;
    }

    private float glyphHeight(float unit) {
        return mHideArrows ? 0f : mArrows.upBox.height() * unit;
    }

    private int arrowAlpha(int color, long kbps) {
        final int alpha = Color.alpha(color);
        return mLiveArrows && kbps < ACTIVE_KBPS ? (int) (alpha * IDLE_ALPHA) : alpha;
    }

    private int pillColor(int color) {
        final int alpha;
        if (mColorMode == COLOR_ACCENT) {
            alpha = 0x38;
        } else if (mColorMode == COLOR_SPEED && color != mIconTint) {
            alpha = 0x33;
        } else {
            alpha = 0x29;
        }
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int contentColor() {
        final boolean lightBar = mDarkIntensity > 0.5f;
        if (mColorMode == COLOR_ACCENT) {
            return mContext.getColor(lightBar
                    ? android.R.color.system_accent1_600 : android.R.color.system_accent1_300);
        }
        if (mColorMode == COLOR_SPEED) {
            final long kBps = ((mShowUp ? mTxKbps : 0) + (mShowDown ? mRxKbps : 0)) / 8;
            if (kBps >= 10000) {
                return lightBar ? 0xFFC2410C : 0xFFFF8A50;
            }
            if (kBps >= 2000) {
                return lightBar ? 0xFF9A5800 : 0xFFF5B84B;
            }
            if (kBps >= 100) {
                return lightBar ? 0xFF1A5FD6 : 0xFF8AB4F8;
            }
        }
        return mIconTint;
    }

    private boolean dominantUp() {
        if (mShowUp && mShowDown) {
            return mTxKbps > mRxKbps;
        }
        return mShowUp;
    }

    private static double fraction(long kbps) {
        final double kBps = kbps / 8d;
        return kBps < 1d ? 0d : Math.min(1d, Math.log10(1d + kBps) / FULL_SCALE);
    }

    private float dp(float value) {
        return value * mDensity * mSize;
    }

    private float sp(float value) {
        return value * mScaledDensity * mSize;
    }

    private void textStyle(float size, Typeface typeface) {
        mTextPaint.setTextSize(size);
        mTextPaint.setTypeface(typeface);
        mTextPaint.setLetterSpacing(0f);
    }

    private float lineHeight() {
        mTextPaint.getFontMetrics(mMetrics);
        return mMetrics.descent - mMetrics.ascent;
    }

    private float baseline(float centerY) {
        mTextPaint.getFontMetrics(mMetrics);
        return centerY - (mMetrics.ascent + mMetrics.descent) / 2f;
    }

    private String describe() {
        final StringBuilder sb = new StringBuilder();
        if (mShowUp) {
            sb.append(classicText(mTxKbps));
        }
        if (mShowDown) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(classicText(mRxKbps));
        }
        return sb.toString();
    }

    private String classicText(long kbps) {
        final String value;
        final String unit;
        int unitid = 0;
        switch (mUnits) {
            case UNITS_KILOBITS:
                value = String.format("%d", kbps);
                unitid = R.string.kilobitspersecond_short;
                break;
            case UNITS_MEGABITS:
                value = String.format("%.1f", (float) kbps / 1000);
                unitid = R.string.megabitspersecond_short;
                break;
            case UNITS_KILOBYTES:
            case UNITS_AUTOBYTES:
                if (kbps < 8000 || mUnits == UNITS_KILOBYTES) {
                    value = String.format("%.0f", (float) kbps / 8 );
                    unitid = mShowUnits == SHOW_UNITS_COMPACT
                        ? R.string.kilobytespersecond_compact
                        : R.string.kilobytespersecond_short;
                    break;
                }
            case UNITS_MEGABYTES:
                {
                    final String format;
                    if (kbps < 80000) {
                        format = "%.2f";
                    } else if (kbps < 800000) {
                        format = "%.1f";
                    } else {
                        format = "%.0f";
                    }
                    value = String.format(format, (float) kbps / 8000 );
                }
                unitid = mShowUnits == SHOW_UNITS_COMPACT
                    ? R.string.megabytespersecond_compact
                    : R.string.megabytespersecond_short;
                break;
            default:
                value = "unknown";
                break;
        }

        if (mShowUnits > SHOW_UNITS_OFF && unitid != 0) {
            unit = mContext.getString(unitid);
            return value + " " + unit;
        } else {
            return value;
        }
    }

    private String[] speed(long kbps) {
        switch (mUnits) {
            case UNITS_KILOBITS:
                return new String[] { String.format("%d", kbps),
                        mContext.getString(R.string.kilobitspersecond_short), null };
            case UNITS_MEGABITS:
                return new String[] { oneDecimal(kbps / 1000f),
                        mContext.getString(R.string.megabitspersecond_short), null };
            case UNITS_KILOBYTES:
                return kiloBytes(kbps);
            case UNITS_MEGABYTES:
                return megaBytes(kbps);
            default:
                return kbps < 8000 ? kiloBytes(kbps) : megaBytes(kbps);
        }
    }

    private String[] kiloBytes(long kbps) {
        return new String[] { String.format("%.0f", kbps / 8f),
                mContext.getString(R.string.kilobytespersecond_short),
                mContext.getString(R.string.kilobytespersecond_compact) };
    }

    private String[] megaBytes(long kbps) {
        return new String[] { oneDecimal(kbps / 8000f),
                mContext.getString(R.string.megabytespersecond_short),
                mContext.getString(R.string.megabytespersecond_compact) };
    }

    private static String oneDecimal(float value) {
        return String.format(value < 10f ? "%.1f" : "%.0f", value);
    }

    private String compactText(long kbps) {
        final String[] speed = speed(kbps);
        if (mShowUnits == SHOW_UNITS_OFF) {
            return speed[0];
        }
        if (mShowUnits == SHOW_UNITS_COMPACT && speed[2] != null) {
            return speed[0] + speed[2];
        }
        return speed[0] + " " + speed[1];
    }

    private String fullText(long kbps) {
        final String[] speed = speed(kbps);
        return mShowUnits == SHOW_UNITS_OFF ? speed[0] : speed[0] + " " + speed[1];
    }

    private void unregisterNetworkCallbacks() {
        if (mNetworkCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
        }
        if (mDefaultNetworkCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(mDefaultNetworkCallback);
            mDefaultNetworkCallback = null;
        }
    }

    private void manageNetworkCallbacks() {
        if (mMode == MODE_DISABLED) {
            // Unregister callbacks if disabling
            unregisterNetworkCallbacks();
            return;
        }

        // Register callbacks if enabling
        if (mNetworkCallback == null) {
            mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLinkPropertiesChanged(Network network,
                        LinkProperties linkProperties) {
                    Message msg = new Message();
                    msg.what = MESSAGE_TYPE_ADD_NETWORK;
                    msg.obj = new LinkPropertiesHolder(network, linkProperties);
                    mTrafficHandler.sendMessage(msg);
                }

                @Override
                public void onLost(Network network) {
                    Message msg = new Message();
                    msg.what = MESSAGE_TYPE_REMOVE_NETWORK;
                    msg.obj = network;
                    mTrafficHandler.sendMessage(msg);
                }
            };

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();

            mConnectivityManager.registerNetworkCallback(request, mNetworkCallback);
        }

        if (mDefaultNetworkCallback == null) {
            mDefaultNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateViewState();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    final boolean mobile =
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    if (mobile != mOnMobile) {
                        mOnMobile = mobile;
                        updateViewState();
                    }
                }

                @Override
                public void onLost(Network network) {
                    mOnMobile = false;
                    updateViewState();
                }
            };

            mConnectivityManager.registerDefaultNetworkCallback(mDefaultNetworkCallback);
        }
    }

    public void setViewPosition(int vpos) {
        mViewPosition = vpos;
    }

    private final LineageStatusBarItem.DarkReceiver mDarkReceiver =
            new LineageStatusBarItem.DarkReceiver() {
        public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
            mIconTint = tint;
            mDarkIntensity = darkIntensity;
            setTextColor(mIconTint);
            invalidate();
        }
        public void setFillColors(int darkColor, int lightColor) {
        }
    };

    private final LineageStatusBarItem.VisibilityReceiver mVisibilityReceiver =
            new LineageStatusBarItem.VisibilityReceiver() {
        public void onVisibilityChanged(boolean isVisible) {
            if (mNetworkTrafficIsVisible != isVisible) {
                mNetworkTrafficIsVisible = isVisible;
                updateViewState();
            }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mManager = LineageStatusBarItem.findManager(this);
        if (mManager != null) {
            mManager.addDarkReceiver(mDarkReceiver);
            mManager.addVisibilityReceiver(mVisibilityReceiver);
        }

        mObserver.observe();
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mManager != null) {
            mManager.removeDarkReceiver(mDarkReceiver);
            mManager.removeVisibilityReceiver(mVisibilityReceiver);
            mManager = null;
        }

        mObserver.unobserve();
        mTrafficHandler.removeCallbacksAndMessages(null);
        unregisterNetworkCallbacks();
    }

    class SettingsObserver extends ContentObserver {
        private final String[] mKeys = {
            LineageSettings.Secure.NETWORK_TRAFFIC_MODE,
            LineageSettings.Secure.NETWORK_TRAFFIC_POSITION,
            LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE,
            LineageSettings.Secure.NETWORK_TRAFFIC_HIDE_ARROWS,
            LineageSettings.Secure.NETWORK_TRAFFIC_UNITS,
            LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_UNITS,
            LineageSettings.Secure.NETWORK_TRAFFIC_STYLE,
            LineageSettings.Secure.NETWORK_TRAFFIC_ARROWS,
            LineageSettings.Secure.NETWORK_TRAFFIC_SIZE,
            LineageSettings.Secure.NETWORK_TRAFFIC_COLOR,
            LineageSettings.Secure.NETWORK_TRAFFIC_LIVE_ARROWS,
            LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD,
            LineageSettings.Secure.NETWORK_TRAFFIC_REFRESH,
            LineageSettings.Secure.NETWORK_TRAFFIC_MOBILE_ONLY,
        };

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            for (String key : mKeys) {
                resolver.registerContentObserver(LineageSettings.Secure.getUriFor(key),
                        false, this, UserHandle.USER_ALL);
            }
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private boolean isConnectionAvailable() {
        return mConnectivityManager.getActiveNetwork() != null;
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mMode = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_MODE, 0);
        mPosition = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_POSITION, POSITION_CENTER);
        mAutoHide = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE, 0) == 1;
        mHideArrows = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_HIDE_ARROWS, 0) == 1;
        mUnits = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_UNITS, UNITS_KILOBYTES);
        mShowUnits = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_UNITS, SHOW_UNITS_ON);
        mStyle = Math.max(STYLE_CLASSIC, Math.min(STYLE_RING, LineageSettings.Secure.getInt(
                resolver, LineageSettings.Secure.NETWORK_TRAFFIC_STYLE, STYLE_CLASSIC)));
        mArrows = NetworkTrafficArrows.get(LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_ARROWS, 0));
        mSize = Math.max(60, Math.min(160, LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_SIZE, 100))) / 100f;
        mColorMode = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_COLOR, COLOR_STATUS_BAR);
        mLiveArrows = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_LIVE_ARROWS, 1) == 1;
        mRefreshMs = Math.max(1, Math.min(4, LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_REFRESH, 2))) * 1000;
        mMobileOnly = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_MOBILE_ONLY, 0) == 1;
        final int threshold = LineageSettings.Secure.getInt(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 0);

        manageNetworkCallbacks();

        switch (mUnits) {
            case UNITS_KILOBITS:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_KILOBITS;
                break;
            case UNITS_MEGABITS:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_MEGABITS;
                break;
            case UNITS_KILOBYTES:
            case UNITS_AUTOBYTES:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_KILOBYTES;
                break;
            case UNITS_MEGABYTES:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_MEGABYTES;
                break;
            default:
                mAutoHideThreshold = 0;
                break;
        }
        if (threshold > 0) {
            mAutoHideThreshold = threshold * 8L;
        }

        updateViewState();
    }

    private void updateViewState() {
        mTrafficHandler.sendEmptyMessage(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private static class LinkPropertiesHolder {
        private final Network mNetwork;
        private final LinkProperties mLinkProperties;

        public LinkPropertiesHolder(Network network, LinkProperties linkProperties) {
            mNetwork = network;
            mLinkProperties = linkProperties;
        }

        public Network getNetwork() {
            return mNetwork;
        }

        public LinkProperties getLinkProperties() {
            return mLinkProperties;
        }
    }
}
