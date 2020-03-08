package com.cdot.ping;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

class DeviceDataBaseAdapter {
    private static final String TAG = "DeviceDataBaseAdapter";

    private static final String DB_NAME = "device.db";
    static final String DEVICE_OTHERS = "others";
    static final String TABLE_ROWID = "rowid";

    private static final String tableName = "device";

    private DatabaseHelper mDatabaseHelper;
    private SQLiteDatabase mSQLiteDatabase;

    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DB_NAME, (SQLiteDatabase.CursorFactory) null, 1);
        }

        public void onCreate(SQLiteDatabase db) {
        }

        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS notes");
            onCreate(db);
        }
    }

    DeviceDataBaseAdapter(Context context) {
        mDatabaseHelper = new DatabaseHelper(context);
        mSQLiteDatabase = mDatabaseHelper.getWritableDatabase();
    }

    private boolean tableExists() {
        boolean result = false;
        try {
            Cursor cursor = mSQLiteDatabase.rawQuery(
                    "select count(*) as c from sqlite_master where type ='table' and name ='" + tableName.trim() + "' ",
                    null);
            if (cursor.moveToNext() && cursor.getInt(0) > 0) {
                result = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "error in tableExists:" + e.toString());
        }
        return result;
    }

    private void createTable() {
        Log.v("CreateTable_tableName", tableName);
        mSQLiteDatabase.execSQL("CREATE TABLE \"" + tableName + "\" ("
                + TABLE_ROWID + " LONG NOT NULL DEFAULT 0,"
                + DeviceRecord.DEVICE_NAME + " TEXT,"
                + DeviceRecord.DEVICE_ADDRESS + " TEXT,"
                + DeviceRecord.DEVICE_TYPE + " INTEGER NOT NULL DEFAULT 0,"
                + DEVICE_OTHERS + " INTEGER NOT NULL DEFAULT 0)");
    }

    void close() {
        this.mDatabaseHelper.close();
    }

    long insertData(long rowID, String name, String mac, int bletype, int others) {
        if (!tableExists())
            createTable();
        ContentValues initialValues = new ContentValues();
        initialValues.put(TABLE_ROWID, rowID);
        initialValues.put(DeviceRecord.DEVICE_NAME, name);
        initialValues.put(DeviceRecord.DEVICE_ADDRESS, mac);
        initialValues.put(DeviceRecord.DEVICE_TYPE, bletype);
        initialValues.put(DEVICE_OTHERS, others);
        return mSQLiteDatabase.insert(tableName, TABLE_ROWID, initialValues);
    }

    public boolean deleteData(long rowId) {
        return mSQLiteDatabase.delete(
                tableName, "rowid=" + rowId, null) > 0;
    }

    Cursor fetchAllData() {
        return mSQLiteDatabase.query(tableName,
                new String[]{TABLE_ROWID, DeviceRecord.DEVICE_NAME, DeviceRecord.DEVICE_ADDRESS, DeviceRecord.DEVICE_TYPE, DEVICE_OTHERS},
                null, null, null, null, null);
    }

    Cursor fetchData(long rowId) throws SQLException {
        Cursor mCursor = mSQLiteDatabase.query(true, tableName,
                new String[]{TABLE_ROWID, DeviceRecord.DEVICE_NAME, DeviceRecord.DEVICE_ADDRESS, DeviceRecord.DEVICE_TYPE, DEVICE_OTHERS},
                "rowid=" + rowId, null, null, null, null, null);
        if (mCursor != null) {
            mCursor.moveToFirst();
        }
        return mCursor;
    }

    public boolean updateData(int rowID, String name, String mac, int bletype, int others) {
        ContentValues args = new ContentValues();
        args.put(TABLE_ROWID, rowID);
        args.put(DeviceRecord.DEVICE_NAME, name);
        args.put(DeviceRecord.DEVICE_ADDRESS, mac);
        args.put(DeviceRecord.DEVICE_TYPE, bletype);
        args.put(DEVICE_OTHERS, others);
        return mSQLiteDatabase.update(tableName, args, "rowid=" + rowID, null) > 0;
    }

    boolean macIsKnown(String address) {
        if (!tableExists())
            return false;
        boolean result = false;
        Cursor mCursor = fetchAllData();
        if (mCursor != null) {
            mCursor.moveToNext();
        }
        int count = mCursor.getCount();
        if (count <= 0) {
            return false;
        }
        int i = 0;
        while (true) {
            if (i >= count) {
                break;
            }
            Cursor cur = fetchData(i);
            if (cur.getString(cur.getColumnIndex(DeviceRecord.DEVICE_ADDRESS)).equalsIgnoreCase(address)) {
                result = true;
                break;
            }
            i++;
        }
        return result;
    }

    int getDeviceCount() {
        if (!tableExists())
            return 0;
        Cursor mCursor = fetchAllData();
        if (mCursor != null) {
            mCursor.moveToNext();
        }
        return mCursor.getCount();
    }

    int getDeviceType(String address) {
        if (!tableExists())
            return 0;

        Cursor mCursor = fetchAllData();
        if (mCursor != null) {
            mCursor.moveToNext();
        }
        int count = mCursor.getCount();
        if (count <= 0) {
            return 0;
        }
        for (int i = 0; i < count; i++) {
            Cursor cur = fetchData(i);
            if (cur.getString(cur.getColumnIndex(DeviceRecord.DEVICE_ADDRESS)).equalsIgnoreCase(address)) {
                return cur.getInt(cur.getColumnIndex(DeviceRecord.DEVICE_TYPE));
            }
        }
        return 0;
    }
}
