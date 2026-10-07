package com.safewatch.app.data

import android.content.Context
import com.safewatch.core.Action
import com.safewatch.core.Category
import com.safewatch.core.MediaKey
import com.safewatch.core.Tag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves the marked and scanned scenes of each video as a small file on the phone. */
object TagStore {
    private fun file(ctx: Context, key: String): File =
        File(File(ctx.filesDir, "tags").apply { mkdirs() }, MediaKey.fileName(key) + ".json")

    @Synchronized
    fun load(ctx: Context, key: String): List<Tag> {
        val f = file(ctx, key)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).getJSONArray("tags")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val category = Category.entries.firstOrNull { it.name == o.optString("category") }
                val action = Action.entries.firstOrNull { it.name == o.optString("action") }
                if (category == null || action == null) null
                else Tag(o.getLong("start"), o.getLong("end"), category, action, o.optInt("level", 3),
                    o.optString("source", Tag.SOURCE_MANUAL))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(ctx: Context, key: String, title: String, tags: List<Tag>) {
        val arr = JSONArray()
        for (t in tags.sortedBy { it.startMs }) {
            arr.put(JSONObject()
                .put("start", t.startMs).put("end", t.endMs)
                .put("category", t.category.name).put("action", t.action.name)
                .put("level", t.level).put("source", t.source))
        }
        file(ctx, key).writeText(JSONObject().put("key", key).put("title", title).put("tags", arr).toString(2))
    }

    fun add(ctx: Context, key: String, title: String, tag: Tag) = save(ctx, key, title, load(ctx, key) + tag)
}
