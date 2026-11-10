package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** A shopping list (Supermercato, Farmacia...). */
class ShoppingList(val id: Long, var name: String)

/** A thing to buy: [text] ("latte"), [qty] (1 = no number shown), ticked when bought ([done]). */
class ShoppingItem(val id: Long, val listId: Long, var text: String, var qty: Int, var done: Boolean)

/** A thing bought before, for the suggestions: how many times and when last. */
class ShoppingHistory(val text: String, val count: Int, val last: Long)

/**
 * The lists, their items (in the order they are shown) and the history of what has been added,
 * kept as JSON in their own SharedPreferences.
 */
object ShoppingStore {

    private fun prefs(context: Context) = context.getSharedPreferences("app.minimalauncher.shopping", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- lists

    fun lists(context: Context): List<ShoppingList> {
        val raw = prefs(context).getString("lists", null)
        val parsed = try {
            val a = JSONArray(raw ?: "[]")
            List(a.length()) { a.getJSONObject(it).let { o -> ShoppingList(o.getLong("id"), o.getString("name")) } }
        } catch (e: Exception) {
            emptyList()
        }
        // There is always one list
        return parsed.ifEmpty { listOf(ShoppingList(1L, context.getString(app.olauncher.R.string.ml_shop_default_list))).also { saveLists(context, it) } }
    }

    private fun saveLists(context: Context, lists: List<ShoppingList>) {
        val a = JSONArray()
        lists.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs(context).edit { putString("lists", a.toString()) }
    }

    fun addList(context: Context, name: String): ShoppingList {
        val lists = lists(context)
        val list = ShoppingList((lists.maxOfOrNull { it.id } ?: 0L) + 1, name)
        saveLists(context, lists + list)
        return list
    }

    fun renameList(context: Context, id: Long, name: String) =
        saveLists(context, lists(context).onEach { if (it.id == id) it.name = name })

    /** Removes a list and its items (the last list is never removed: it is emptied). */
    fun removeList(context: Context, id: Long) {
        val lists = lists(context)
        if (lists.size > 1) saveLists(context, lists.filterNot { it.id == id })
        saveItems(context, items(context).filterNot { it.listId == id })
    }

    fun moveList(context: Context, id: Long, direction: Int) {
        val lists = lists(context).toMutableList()
        val i = lists.indexOfFirst { it.id == id }
        val j = i + direction
        if (i < 0 || j !in lists.indices) return
        lists.add(j, lists.removeAt(i))
        saveLists(context, lists)
    }

    /** The list shown (the last one used). */
    fun currentListId(context: Context): Long =
        prefs(context).getLong("current", 0L).takeIf { id -> lists(context).any { it.id == id } } ?: lists(context).first().id

    fun setCurrentListId(context: Context, id: Long) = prefs(context).edit { putLong("current", id) }

    // ---------------------------------------------------------------- items

    fun items(context: Context): List<ShoppingItem> = try {
        val a = JSONArray(prefs(context).getString("items", "[]"))
        List(a.length()) {
            a.getJSONObject(it).let { o ->
                ShoppingItem(o.getLong("id"), o.getLong("l"), o.getString("t"), o.optInt("q", 1), o.optBoolean("d", false))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun items(context: Context, listId: Long) = items(context).filter { it.listId == listId }

    private fun saveItems(context: Context, items: List<ShoppingItem>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("l", it.listId).put("t", it.text).put("q", it.qty).put("d", it.done)) }
        prefs(context).edit { putString("items", a.toString()) }
    }

    /**
     * Adds "2 latte" (or "latte x2") to [listId]. Already there to buy: its quantity goes up; there
     * but bought: it comes back to buy. New items go at the end of the things to buy.
     */
    fun add(context: Context, listId: Long, input: String) {
        val (text, qty) = parse(input) ?: return
        val all = items(context).toMutableList()
        val existing = all.firstOrNull { it.listId == listId && key(it.text) == key(text) }
        if (existing != null) {
            if (existing.done) {
                existing.done = false
                existing.qty = qty
                // Back among the things to buy, at their end
                all.remove(existing)
                val lastToBuy = all.indexOfLast { it.listId == listId && !it.done }
                all.add(lastToBuy + 1, existing)
            } else existing.qty += qty
        } else {
            val item = ShoppingItem((all.maxOfOrNull { it.id } ?: 0L) + 1, listId, text, qty, false)
            val lastToBuy = all.indexOfLast { it.listId == listId && !it.done }
            all.add(if (lastToBuy >= 0) lastToBuy + 1 else all.size, item)
        }
        saveItems(context, all)
        remember(context, text)
    }

    fun setDone(context: Context, id: Long, done: Boolean) {
        val all = items(context).toMutableList()
        val item = all.firstOrNull { it.id == id } ?: return
        item.done = done
        all.remove(item)
        if (done) all.add(item)
        else {
            // Back to buy: at the end of the things to buy
            val lastToBuy = all.indexOfLast { it.listId == item.listId && !it.done }
            all.add(lastToBuy + 1, item)
        }
        saveItems(context, all)
    }

    fun update(context: Context, id: Long, input: String) {
        val (text, qty) = parse(input) ?: return
        saveItems(context, items(context).onEach { if (it.id == id) { it.text = text; it.qty = qty } })
        remember(context, text)
    }

    fun remove(context: Context, id: Long) = saveItems(context, items(context).filterNot { it.id == id })

    fun clearDone(context: Context, listId: Long) = saveItems(context, items(context).filterNot { it.listId == listId && it.done })

    /** The things to buy of [listId] in this new order (the others keep their place). */
    fun reorder(context: Context, listId: Long, orderedIds: List<Long>) {
        val all = items(context)
        val byId = all.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        if (ordered.size != all.count { it.listId == listId && !it.done }) return
        val queue = ArrayDeque(ordered)
        saveItems(context, all.map { if (it.listId == listId && !it.done) queue.removeFirst() else it })
    }

    /** "2 latte", "2x latte", "latte x2" -> ("latte", 2). Null for an empty text. */
    fun parse(input: String): Pair<String, Int>? {
        var text = input.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        var qty = 1
        Regex("^(\\d{1,3})\\s*[xX×]?\\s+(.+)$").find(text)?.let { m ->
            qty = m.groupValues[1].toInt().coerceAtLeast(1)
            text = m.groupValues[2]
        } ?: Regex("^(.+?)\\s+[xX×]\\s*(\\d{1,3})$").find(text)?.let { m ->
            qty = m.groupValues[2].toInt().coerceAtLeast(1)
            text = m.groupValues[1]
        }
        return text.replaceFirstChar { it.uppercase() } to qty
    }

    // ---------------------------------------------------------------- history

    fun history(context: Context): List<ShoppingHistory> = try {
        val a = JSONArray(prefs(context).getString("history", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> ShoppingHistory(o.getString("t"), o.getInt("c"), o.getLong("w")) } }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveHistory(context: Context, history: List<ShoppingHistory>) {
        val a = JSONArray()
        // The 300 most used are plenty
        history.sortedWith(compareByDescending<ShoppingHistory> { it.count }.thenByDescending { it.last }).take(300)
            .forEach { a.put(JSONObject().put("t", it.text).put("c", it.count).put("w", it.last)) }
        prefs(context).edit { putString("history", a.toString()) }
    }

    private fun remember(context: Context, text: String) {
        val history = history(context)
        val old = history.firstOrNull { key(it.text) == key(text) }
        saveHistory(context, history.filterNot { it === old } + ShoppingHistory(text, (old?.count ?: 0) + 1, System.currentTimeMillis()))
    }

    fun forget(context: Context, text: String) = saveHistory(context, history(context).filterNot { key(it.text) == key(text) })

    /**
     * Suggestions for what is being typed: what starts with it first, then what contains it; the
     * most bought first. Empty text: the most bought. What is already to buy in [listId] is left out.
     */
    fun suggestions(context: Context, listId: Long, typed: String, max: Int = 8): List<String> {
        val q = key(parse(typed)?.first ?: "")
        val toBuy = items(context, listId).filter { !it.done }.map { key(it.text) }.toSet()
        val candidates = history(context).filter { key(it.text) !in toBuy && key(it.text) != q }
            .sortedWith(compareByDescending<ShoppingHistory> { it.count }.thenByDescending { it.last })
        if (q.isEmpty()) return candidates.take(max).map { it.text }
        val starts = candidates.filter { key(it.text).startsWith(q) }
        val words = candidates.filter { it !in starts && key(it.text).split(' ').any { w -> w.startsWith(q) } }
        val contains = candidates.filter { it !in starts && it !in words && key(it.text).contains(q) }
        return (starts + words + contains).take(max).map { it.text }
    }

    /** Lowercase, without accents: "Caffè" and "caffe" are the same thing. */
    fun key(text: String) = Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
