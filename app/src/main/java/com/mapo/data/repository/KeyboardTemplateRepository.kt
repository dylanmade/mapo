package com.mapo.data.repository

import com.mapo.data.db.KeyboardTemplateDao
import com.mapo.data.defaults.DefaultLayouts
import com.mapo.data.model.GridLayout
import com.mapo.data.model.KeyboardTemplate
import com.mapo.data.model.TemplateRef
import com.mapo.data.model.toNewTemplateEntity
import com.mapo.data.model.toUserTemplateRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyboardTemplateRepository @Inject constructor(
    private val dao: KeyboardTemplateDao
) {

    /**
     * Built-in templates derived from [DefaultLayouts]. Their `key` is the default layout's name
     * and is stable across releases.
     */
    val builtIns: List<TemplateRef.BuiltIn> = DefaultLayouts.all.map { layout ->
        TemplateRef.BuiltIn(
            key = layout.name,
            name = layout.name,
            columns = layout.columns,
            rows = layout.rows,
            buttons = layout.buttons,
            backgroundColorArgb = layout.backgroundColorArgb
        )
    }

    /** All templates: built-ins first, then user templates sorted by name. */
    val allTemplates: Flow<List<TemplateRef>> = dao.getAll().map { user ->
        builtIns + user.map { it.toUserTemplateRef() }
    }

    val userTemplates: Flow<List<TemplateRef.User>> = dao.getAll().map { list ->
        list.map { it.toUserTemplateRef() }
    }

    suspend fun findByName(name: String): TemplateRef? {
        builtIns.firstOrNull { it.name.equals(name, ignoreCase = false) }?.let { return it }
        return dao.getByNameOnce(name)?.toUserTemplateRef()
    }

    /** Insert [layout] as a new user template. Throws if the name collides at the DB level. */
    suspend fun insertNew(layout: GridLayout, templateName: String): Long =
        dao.insert(layout.toNewTemplateEntity(templateName))

    /** Replace contents of an existing user template (by id) with [layout]'s grid (name kept). */
    suspend fun updateExisting(templateId: Long, layout: GridLayout) {
        val updated = KeyboardTemplate(
            id = templateId,
            name = (dao.getAllOnce().firstOrNull { it.id == templateId }?.name)
                ?: return,
            columns = layout.columns,
            rows = layout.rows,
            buttonsJson = layout.toNewTemplateEntity("").buttonsJson,
            backgroundColorArgb = layout.backgroundColorArgb
        )
        dao.update(updated)
    }
}
