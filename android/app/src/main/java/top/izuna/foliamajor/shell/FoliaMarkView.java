package top.izuna.foliamajor.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

// android/app/src/main/java/top/izuna/foliamajor/shell/FoliaMarkView.java
// 启动页上的 F 标记：和 public/icon.svg 同一套几何（250×340 的外接框、四块圆角矩形）。
// 直接画而不是放位图，任何尺寸都不会糊。
public class FoliaMarkView extends View {

    private static final float MARK_WIDTH = 250f;
    private static final float MARK_HEIGHT = 340f;
    private static final float CORNER = 10f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF block = new RectF();
    private final int red;
    private final int yellow;
    private final int blue;
    private final int white;

    public FoliaMarkView(Context context) {
        this(context, null);
    }

    public FoliaMarkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        red = context.getColor(R.color.folia_red);
        yellow = context.getColor(R.color.folia_yellow);
        blue = context.getColor(R.color.folia_blue);
        white = context.getColor(R.color.folia_white);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int height = (int) (96 * density + 0.5f);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        }
        int width = Math.round(height * MARK_WIDTH / MARK_HEIGHT);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float scale = getHeight() / MARK_HEIGHT;
        final float originX = (getWidth() - MARK_WIDTH * scale) / 2f;
        final float originY = (getHeight() - MARK_HEIGHT * scale) / 2f;
        final float radius = CORNER * scale;

        drawBlock(canvas, originX, originY, scale, radius, 0, 0, 88, 156, red);
        drawBlock(canvas, originX, originY, scale, radius, 100, 0, 150, 84, yellow);
        drawBlock(canvas, originX, originY, scale, radius, 0, 168, 200, 80, blue);
        drawBlock(canvas, originX, originY, scale, radius, 0, 260, 88, 80, white);
    }

    private void drawBlock(Canvas canvas, float originX, float originY, float scale, float radius,
                           float x, float y, float width, float height, int color) {
        block.set(
                originX + x * scale,
                originY + y * scale,
                originX + (x + width) * scale,
                originY + (y + height) * scale);
        paint.setColor(color);
        canvas.drawRoundRect(block, radius, radius, paint);
    }
}
