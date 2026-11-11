package net.mosslive.notebook

/**
 * 内置默认笔记的定义。
 *
 * 正文在 `app/src/main/assets/default-note-usage.md`：打包 APK 时即固定进包，
 * 运行时只做一次「资产文件 → notes 目录」的拷贝，不联网、不调模型。
 */
object DefaultNotes {

    /**
     * 已塞过说明的标记值。**不要往上加**：判据是「>= 本值即视为已塞」，
     * 加一版会让已经删掉这条说明的用户被重新塞回来。
     */
    const val SEEDED = 1

    /** 固定 id：判重、日后取证都用它，不用扫目录找标题。 */
    const val GUIDE_ID = "guide-使用说明"
    const val GUIDE_TITLE = "使用说明"
    const val GUIDE_ASSET = "default-note-usage.md"
}
