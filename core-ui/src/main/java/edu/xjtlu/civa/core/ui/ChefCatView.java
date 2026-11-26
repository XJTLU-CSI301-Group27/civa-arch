package edu.xjtlu.civa.core.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Internal animated artwork primitive: the same original chef kitten in every new scene. */
public final class ChefCatView extends View {
    private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float stride, sitting = 1f, wave;
    private boolean peeking;
    private static final int INK = 0xFF795034, FUR = 0xFFE6AC61, CREAM = 0xFFFFF5DF;
    private static final int BLUE = 0xFF718994, STRIPE = 0xFFC58041;

    public ChefCatView(Context context) { this(context, null); }
    public ChefCatView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }
    public void pose(float stride, float sitting, float wave, boolean peeking) {
        this.stride = stride; this.sitting = sitting; this.wave = wave; this.peeking = peeking;
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 200f, getHeight() / 250f);
        canvas.save(); canvas.translate((getWidth() - 200 * scale) / 2f, getHeight() - 250 * scale);
        canvas.scale(scale, scale);
        float bounce = (1 - sitting) * (float)Math.abs(Math.sin(stride * 2)) * 5;
        canvas.translate(0, -bounce);
        // A peeking kitten has its legs/tail hidden behind the panel.
        if (!peeking) {
        // Curled orange tail behind the apron.
        brush.setStyle(Paint.Style.STROKE); brush.setStrokeCap(Paint.Cap.ROUND); brush.setStrokeJoin(Paint.Join.ROUND);
        Path tail = new Path(); tail.moveTo(132, 221); tail.cubicTo(186, 235, 188, 178, 163, 183);
        stroke(canvas, tail, INK, 23); stroke(canvas, tail, FUR, 17);
        line(canvas, 167, 208, 178, 211, STRIPE, 4); line(canvas, 174, 193, 182, 196, STRIPE, 4);
        // Feet swing in opposite directions during the run-in, then tuck under the body.
        for (int side = -1; side <= 1; side += 2) {
            canvas.save(); canvas.rotate((1 - sitting) * (float)Math.sin(stride) * 30 * side, 100 + side * 25, 190);
            oval(canvas, 100 + side * 25 - 19, 191, 100 + side * 25 + 19, 239, FUR, INK, 3);
            oval(canvas, 100 + side * 25 - 23, 224, 100 + side * 25 + 22, 241, CREAM, INK, 2.5f);
            line(canvas, 100 + side * 25 - 5, 231, 100 + side * 25 - 5, 237, STRIPE, 1.6f);
            line(canvas, 100 + side * 25 + 4, 231, 100 + side * 25 + 4, 237, STRIPE, 1.6f);
            canvas.restore();
        }
        }
        oval(canvas, 57, 124, 144, 232, FUR, INK, 3);
        Path apron = new Path(); apron.moveTo(75, 139); apron.quadTo(100, 150, 127, 139);
        apron.lineTo(143, 221); apron.quadTo(102, 237, 57, 220); apron.close();
        fill(canvas, apron, CREAM); stroke(canvas, apron, BLUE, 3.5f);
        line(canvas, 76, 139, 66, 175, BLUE, 4); line(canvas, 126, 138, 136, 173, BLUE, 4);
        Path pocket = new Path(); pocket.moveTo(87, 190); pocket.lineTo(116, 190); pocket.lineTo(113, 208);
        pocket.quadTo(100, 217, 89, 207); pocket.close(); fill(canvas, pocket, BLUE);
        line(canvas, 92, 196, 110, 196, 0xFFBFCBD0, 1.5f);
        // Resting paws / the waving paw. In the peeking pose they overlap the panel rim.
        oval(canvas, 119, 157, 149, peeking ? 242 : 217, CREAM, INK, 3);
        canvas.save(); canvas.rotate(-wave * 31 - (peeking ? 8 : 0), 65, 163);
        oval(canvas, 43, peeking ? 116 : 160, 74, peeking ? 205 : 217, FUR, INK, 3);
        oval(canvas, 40, peeking ? 110 : 198, 77, peeking ? 139 : 224, CREAM, INK, 2.5f);
        canvas.restore();
        if (peeking) oval(canvas, 119, 220, 153, 244, CREAM, INK, 2.6f);
        // Face and soft triangular ears.
        Path face = new Path(); face.moveTo(52, 81); face.lineTo(45, 45); face.quadTo(46, 37, 54, 43);
        face.lineTo(77, 61); face.quadTo(100, 52, 125, 61); face.lineTo(150, 42);
        face.quadTo(157, 39, 155, 49); face.lineTo(151, 84);
        face.cubicTo(174, 142, 128, 159, 100, 157); face.cubicTo(68, 158, 27, 141, 52, 81); face.close();
        fill(canvas, face, FUR); stroke(canvas, face, INK, 3.2f);
        Path inner = new Path(); inner.moveTo(53, 51); inner.lineTo(57, 77); inner.lineTo(71, 66); inner.close();
        inner.moveTo(146, 51); inner.lineTo(132, 66); inner.lineTo(147, 77); inner.close(); fill(canvas, inner, 0xFFD8947B);
        Path muzzle = new Path(); muzzle.moveTo(53, 116); muzzle.quadTo(72, 104, 83, 119);
        muzzle.quadTo(99, 134, 117, 119); muzzle.quadTo(134, 103, 151, 116);
        muzzle.quadTo(161, 149, 100, 154); muzzle.quadTo(41, 147, 53, 116); fill(canvas, muzzle, CREAM);
        line(canvas, 89, 65, 95, 84, STRIPE, 4); line(canvas, 101, 63, 103, 84, STRIPE, 4);
        line(canvas, 114, 65, 111, 83, STRIPE, 4);
        line(canvas, 49, 101, 64, 106, STRIPE, 3); line(canvas, 48, 111, 62, 113, STRIPE, 3);
        line(canvas, 136, 105, 151, 100, STRIPE, 3); line(canvas, 138, 114, 153, 111, STRIPE, 3);
        oval(canvas, 71, 102, 79, 113, INK, INK, 1); oval(canvas, 122, 102, 130, 113, INK, INK, 1);
        oval(canvas, 61, 117, 77, 125, 0x77D8947B, 0, 0); oval(canvas, 126, 117, 142, 125, 0x77D8947B, 0, 0);
        Path nose = new Path(); nose.moveTo(94, 122); nose.quadTo(101, 117, 108, 122); nose.lineTo(101, 128); nose.close(); fill(canvas, nose, INK);
        Path smile = new Path(); smile.moveTo(101, 127); smile.quadTo(99, 141, 90, 134);
        smile.moveTo(101, 127); smile.quadTo(103, 141, 113, 134); stroke(canvas, smile, INK, 2.3f);
        line(canvas, 59, 126, 38, 122, INK, 1.7f); line(canvas, 59, 133, 37, 136, INK, 1.7f);
        line(canvas, 142, 125, 165, 121, INK, 1.7f); line(canvas, 142, 133, 165, 136, INK, 1.7f);
        // Big flour-white chef hat, warm outline and little stitched folds.
        Path hat = new Path(); hat.moveTo(62, 49); hat.cubicTo(36, 47, 40, 20, 61, 20);
        hat.cubicTo(57, 1, 84, 0, 94, 13); hat.cubicTo(108, -2, 134, 7, 132, 23);
        hat.cubicTo(154, 26, 148, 49, 131, 49); hat.lineTo(130, 65); hat.quadTo(99, 75, 63, 65); hat.close();
        fill(canvas, hat, 0xFFFFFBF2); stroke(canvas, hat, INK, 2.8f);
        Path band = new Path(); band.moveTo(61, 49); band.quadTo(99, 56, 133, 49); stroke(canvas, band, 0xFFCCB28B, 2);
        line(canvas, 76, 25, 80, 43, 0xFFCCB28B, 2); line(canvas, 117, 25, 113, 43, 0xFFCCB28B, 2);
        // Little blue neckerchief belongs to the same bakery palette as the home art.
        Path scarf = new Path(); scarf.moveTo(73, 153); scarf.quadTo(101, 164, 131, 151);
        scarf.lineTo(130, 160); scarf.quadTo(102, 173, 73, 162); scarf.close(); fill(canvas, scarf, BLUE);
        canvas.restore();
    }
    private void fill(Canvas c, Path path, int color) { brush.setStyle(Paint.Style.FILL); brush.setColor(color); c.drawPath(path, brush); }
    private void stroke(Canvas c, Path path, int color, float width) { brush.setStyle(Paint.Style.STROKE); brush.setColor(color); brush.setStrokeWidth(width); c.drawPath(path, brush); }
    private void line(Canvas c, float x, float y, float x2, float y2, int color, float width) { brush.setStyle(Paint.Style.STROKE); brush.setColor(color); brush.setStrokeWidth(width); brush.setStrokeCap(Paint.Cap.ROUND); c.drawLine(x,y,x2,y2,brush); }
    private void oval(Canvas c, float l, float t, float r, float b, int color, int stroke, float width) {
        brush.setStyle(Paint.Style.FILL); brush.setColor(color); c.drawOval(new RectF(l,t,r,b),brush);
        if (width > 0) { brush.setStyle(Paint.Style.STROKE); brush.setColor(stroke); brush.setStrokeWidth(width); c.drawOval(new RectF(l,t,r,b),brush); }
    }
}
