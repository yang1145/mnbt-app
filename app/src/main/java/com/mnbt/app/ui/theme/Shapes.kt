package com.mnbt.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 按钮/输入框 8dp、卡片 12dp、弹框 16dp（PRD §8.2）。 */
object AppCorner {
    val Control = RoundedCornerShape(8.dp)
    val Card = RoundedCornerShape(12.dp)

    /** 悬浮居中的对话框：四角都圆。 */
    val Dialog = RoundedCornerShape(16.dp)

    /** 仅给贴底的 ModalBottomSheet 用（底边贴屏幕，所以底部不收圆角），不要拿去当对话框形状。 */
    val Sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** 密度规范（PRD §8.2）：行高、边距、间距统一从这里取。 */
object AppDimens {
    val RowHeight = 56.dp
    val RowHeightWithSubtitle = 72.dp
    val ScreenPadding = 16.dp
    val CardPadding = 16.dp
    val CardGap = 8.dp
    /** 分层用 1px 边不用阴影 */
    val Hairline = 1.dp
}
