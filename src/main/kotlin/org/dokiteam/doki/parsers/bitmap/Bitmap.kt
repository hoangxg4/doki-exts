package org.dokiteam.doki.parsers.bitmap

public interface Bitmap {

	public val width: Int
	public val height: Int

	public fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect)
}
