package com.localdrop.host

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HostDatabase(context: Context) : SQLiteOpenHelper(context, "localdrop-pro.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, body TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE devices (device_id TEXT PRIMARY KEY, name TEXT NOT NULL, ip TEXT, last_seen INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized fun addMessage(sender: String, body: String): JSONObject {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put("sender", sender.take(100))
            put("body", body.take(4000))
            put("created_at", time)
        }
        val id = writableDatabase.insert("messages", null, values)
        return JSONObject().put("id", id).put("from", sender).put("text", body).put("time", time)
    }

    @Synchronized fun getMessages(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT id,sender,body,created_at FROM messages ORDER BY id DESC LIMIT 200", null).use { c ->
            val rows = ArrayList<JSONObject>()
            while (c.moveToNext()) {
                rows.add(JSONObject().put("id", c.getLong(0)).put("from", c.getString(1))
                    .put("text", c.getString(2)).put("time", c.getString(3)))
            }
            rows.asReversed().forEach { result.put(it) }
        }
        return result
    }

    @Synchronized fun registerDevice(id: String, name: String, ip: String): JSONObject {
        val values = ContentValues().apply {
            put("device_id", id.take(128))
            put("name", name.take(100))
            put("ip", ip.take(64))
            put("last_seen", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("devices", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return JSONObject().put("id", id).put("name", name).put("ip", ip).put("lastSeen", System.currentTimeMillis())
    }

    @Synchronized fun getDevices(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT device_id,name,ip,last_seen FROM devices ORDER BY last_seen DESC", null).use { c ->
            while (c.moveToNext()) {
                result.put(JSONObject().put("id", c.getString(0)).put("name", c.getString(1))
                    .put("ip", c.getString(2)).put("lastSeen", c.getLong(3)))
            }
        }
        return result
    }
}
