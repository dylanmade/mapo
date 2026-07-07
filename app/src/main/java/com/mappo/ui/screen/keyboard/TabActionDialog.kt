package com.mappo.ui.screen.keyboard

import com.mappo.data.model.TemplateRef

/**
 * State machine for the keyboard-tab action dialog tree. MainScreen hoists a single
 * `TabActionDialog?` and renders the matching dialog. "Cycle back" handlers reassign
 * the state to a previous variant.
 */
sealed class TabActionDialog {

    data class RemoveConfirm(
        val layoutId: Long,
        val name: String,
        val profileName: String
    ) : TabActionDialog()

    data class SaveTemplateChooser(
        val layoutId: Long,
        val keyboardName: String
    ) : TabActionDialog()

    data class SaveAsNewTemplate(
        val layoutId: Long,
        val keyboardName: String,
        val templateName: String
    ) : TabActionDialog()

    data class UpdateExistingTemplate(
        val layoutId: Long,
        val keyboardName: String,
        val filter: String
    ) : TabActionDialog()

    data class UpdateTemplateConfirm(
        val layoutId: Long,
        val keyboardName: String,
        val target: TemplateRef.User,
        val previous: TabActionDialog
    ) : TabActionDialog()

    data class TemplateNameConflict(
        val layoutId: Long,
        val keyboardName: String,
        val templateName: String,
        val existing: TemplateRef
    ) : TabActionDialog()

    object AddKeyboardChooser : TabActionDialog()

    object AddFromTemplate : TabActionDialog()

    object AddFromProfile : TabActionDialog()

    data class AddFromProfileLayout(
        val profileId: Long,
        val profileName: String
    ) : TabActionDialog()

    data class RemoveButtonConfirm(
        val buttonId: String,
        val buttonLabel: String
    ) : TabActionDialog()
}
