package com.lingxi.chat;

/**
 * 手指特效的取景换算：TextureView.getBitmap() 拿到的是**纹理内容**按请求尺寸拉伸后的图，
 * 不含 setTransform 那次旋转，所以既不能直接当屏幕内容喂识别库，也不能拿它的归一化坐标乘视图宽高。
 *
 * <p>这里只留纯算数（转正后的画幅尺寸、铺满系数、屏幕上看得见的那块区域），
 * 真正贴到画布上时复用屏幕上那块 Matrix，保证「看到的」和「算出来的」是同一次变换。
 */
public final class FxFraming {

    private FxFraming() {
    }

    /** 需要转 90°/270° 才正过来的感应器角度 */
    public static boolean swapped(int sensorDeg) {
        int r = Math.abs(sensorDeg) % 180;
        return r == 90;
    }

    /** 预览尺寸转正之后（屏幕上那个朝向）的宽/高，单位与传入一致，只用比例 */
    public static int frameW(int previewW, int previewH, int sensorDeg) {
        return swapped(sensorDeg) ? previewH : previewW;
    }

    public static int frameH(int previewW, int previewH, int sensorDeg) {
        return swapped(sensorDeg) ? previewW : previewH;
    }

    /** 铺满：整幅画面放大到刚好盖住目标，多出来的部分被裁掉 */
    public static float fillScale(float targetW, float targetH, float srcW, float srcH) {
        if (srcW <= 0f || srcH <= 0f || targetW <= 0f || targetH <= 0f) return 1f;
        return Math.max(targetW / srcW, targetH / srcH);
    }

    /**
     * 抓帧该用的尺寸：必须和纹理本身同一个纵横比，否则内容会被拉扁。
     * 返回 {宽, 高}，宽固定为 wantW，高按预览比例算并保证是偶数（编码器对奇数尺寸不友好）。
     */
    public static int[] captureSize(int wantW, int previewW, int previewH) {
        int h = Math.round(wantW * (previewH / (float) previewW));
        if (h < 2) h = 2;
        if (h % 2 != 0) h += 1;
        return new int[]{wantW, h};
    }

    /**
     * 合成目标尺寸：和屏幕同一个纵横比，这样归一化坐标乘上宽高就是屏幕上的位置。
     * 返回 {宽, 高}。
     */
    public static int[] targetSize(int wantW, float viewW, float viewH) {
        int h = Math.round(wantW * (viewH / viewW));
        if (h < 2) h = 2;
        if (h % 2 != 0) h += 1;
        return new int[]{wantW, h};
    }
}
