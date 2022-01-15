package org.thoughtcrime.securesms.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.MmsSmsColumns.Types
import org.thoughtcrime.securesms.testing.TestDatabaseUtil

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MmsDatabaseTest {
  private lateinit var db: SQLiteDatabase
  private lateinit var mmsDatabase: MmsDatabase

  @Before
  fun setup() {
    val sqlCipher = TestDatabaseUtil.inMemoryDatabase {
      execSQL(MmsDatabase.CREATE_TABLE)
    }

    db = sqlCipher.writableDatabase
    mmsDatabase = MmsDatabase(ApplicationProvider.getApplicationContext(), sqlCipher)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `isGroupQuitMessage when normal message, return false`() {
    val id = TestMms.insert(db, type = Types.BASE_SENDING_TYPE or Types.SECURE_MESSAGE_BIT or Types.PUSH_MESSAGE_BIT)
    assertFalse(mmsDatabase.isGroupQuitMessage(id))
  }

  @Test
  fun `isGroupQuitMessage when legacy quit message, return true`() {
    val id = TestMms.insert(db, type = Types.BASE_SENDING_TYPE or Types.SECURE_MESSAGE_BIT or Types.PUSH_MESSAGE_BIT or Types.GROUP_LEAVE_BIT)
    assertTrue(mmsDatabase.isGroupQuitMessage(id))
  }

  @Test
  fun `isGroupQuitMessage when GV2 leave update, return false`() {
    val id = TestMms.insert(db, type = Types.BASE_SENDING_TYPE or Types.SECURE_MESSAGE_BIT or Types.PUSH_MESSAGE_BIT or Types.GROUP_LEAVE_BIT or Types.GROUP_V2_BIT or Types.GROUP_UPDATE_BIT)
    assertFalse(mmsDatabase.isGroupQuitMessage(id))
  }

  @Test
  fun `getLatestGroupQuitTimestamp when only normal message, return -1`() {
    TestMms.insert(db, threadId = 1, sentTimeMillis = 1, type = Types.BASE_SENDING_TYPE or Types.SECURE_MESSAGE_BIT or Types.PUSH_MESSAGE_BIT)
    assertEquals(-1, mmsDatabase.getLatestGroupQuitTimestamp(1, 4))
  }

  @Test
  fun `getLatestGroupQuitTimestamp when legacy quit, return message timestamp`() {
    TestMms.insert(db, threadId = 1, sentTimeMillis = 2, type = Types.BASE_SENDING_TYPE or Types.SECURE_MESSAGE_BIT or Types.PUSH_MESSAGE_BIT or Types.GROUP_LEAVE_BIT)
    assertEquals(2, mmsDatabase.getLatestGroupQuitTimestamp(1, 4))
  }

  @Test
  fun `getLatestGroupQuitTimestamp when GV2 leave update message, return -1`() {
    TestMms.insert(db, threadId = 1, sentTimeMillis = 3, type = Types.BASE_SENDING_TYPE or Types.SECURE_MESSAGE_BIT or Types.PUSH_MESSAGE_BIT or Types.GROUP_LEAVE_BIT or Types.GROUP_V2_BIT or Types.GROUP_UPDATE_BIT)
    assertEquals(-1, mmsDatabase.getLatestGroupQuitTimestamp(1, 4))
  }
}
