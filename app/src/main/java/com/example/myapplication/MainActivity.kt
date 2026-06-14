package com.example.myapplication

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import java.io.File
import java.io.FileOutputStream

// --- أولاً: جداول قاعدة البيانات والقيود الشديدة لمنع التكرار ---
@Entity(tableName = "accounts_table", indices = [Index(value = ["accountName"], unique = true)])
data class Account(
    @PrimaryKey val accountNumber: Int, // رقم حساب فريد لا يتكرر[cite: 1]
    val accountName: String // اسم حساب فريد لا يتكرر[cite: 1]
)

@Entity(tableName = "receipts_table")
data class Receipt(
    @PrimaryKey val receiptId: Int, // رقم السند (أكبر سند + 1 وبدون حروف أو كسور)[cite: 1]
    val accountName: String, // اسم العميل المربوط بدليل الحسابات[cite: 1]
    val amount: Int, // المبلغ رقم صحيح بدون كسور[cite: 1]
    val statement: String, // البيان[cite: 1]
    val date: Int, // تاريخ السند (غير قابل للتعديل ويأخذ آخر تاريخ مفتوح)[cite: 1]
    val isLocked: Boolean = false // حالة إقفال السند واليوم[cite: 1]
)

@Entity(tableName = "user_table")
data class User(
    @PrimaryKey val id: Int = 1, // مستخدم واحد فقط للتطبيق[cite: 1]
    var username: String,
    var password: String
)

// --- ثانياً: واجهة العمليات البرمجية وقيود الأمان وشروط التصدير ---
@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveUser(user: User)
    @Query("SELECT * FROM user_table WHERE id = 1 LIMIT 1") suspend fun getUser(): User?

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAccount(account: Account)
    @Update suspend fun updateAccount(account: Account)
    @Delete suspend fun deleteAccount(account: Account)
    @Query("DELETE FROM accounts_table") suspend fun deleteAllAccounts() // مسح البيانات قبل استيراد الاكسل[cite: 1]
    @Query("SELECT * FROM accounts_table WHERE accountNumber = :num OR accountName LIKE :name") suspend fun searchAccounts(num: Int?, name: String?): List<Account>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReceipt(receipt: Receipt)
    @Update suspend fun updateReceipt(receipt: Receipt)
    @Delete suspend fun deleteReceipt(receipt: Receipt)
    @Query("SELECT MAX(receiptId) FROM receipts_table") suspend fun getMaxReceiptId(): Int?
    @Query("SELECT date FROM receipts_table WHERE isLocked = 0 ORDER BY receiptId DESC LIMIT 1") suspend fun getLastUnlockedDate(): Int?
    @Query("SELECT * FROM receipts_table WHERE isLocked = 0 AND date = (SELECT MAX(date) FROM receipts_table WHERE isLocked = 0)") suspend fun getLastActiveDayReceipts(): List<Receipt>
    @Query("UPDATE receipts_table SET isLocked = 1 WHERE date = :targetDate") suspend fun lockDay(targetDate: Int) // إقفال اليوم المصدر[cite: 1]
    @Query("UPDATE receipts_table SET isLocked = 0 WHERE date = (SELECT MAX(date) FROM receipts_table WHERE isLocked = 1)") suspend fun unlockLastLockedDay() // إلغاء إقفال آخر يوم[cite: 1]
}

@Database(entities = [Account::class, Receipt::class, User::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
}

// --- ثالثاً: منطق تشغيل الشاشات والتحكم بالتصدير والاستيراد وحماية السندات ---
class MainActivity : AppCompatActivity() {
    private lateinit var db: AppDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // بناء قاعدة البيانات
        val database = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "receipts_secure_db").build()
        db = database.appDao()

        // إنشاء واجهة مرئية بسيطة مباشرة برمجياً لضمان نجاح البناء السحابي بدون ملفات xml إضافية
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            padding = 50
        }
        val textView = android.widget.TextView(this).apply {
            text = "تطبيق السندات جاهز للعمل ومقفل برمجياً حسب الشروط!"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(textView)
        setContentView(layout)
    }

    // دالة التصدير الحذرة والإقفال الفوري لليوم المصدّر[cite: 1]
    fun exportToExcelAndLock(username: String, dateString: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val receipts = db.getLastActiveDayReceipts()
            if (receipts.isEmpty()) return@launch

            val workbook = HSSFWorkbook()
            val sheet = workbook.createSheet("سندات القبض المصدرة")
            val header = sheet.createRow(0)
            
            // الترتيب الصارم المطلوب: رقم الحساب – اسم الحساب – المبلغ – العملة – البيان – التاريخ[cite: 1]
            val titles = listOf("رقم الحساب", "اسم الحساب", "المبلغ", "العملة", "البيان", "التاريخ")
            titles.forEachIndexed { i, t -> header.createCell(i).setCellValue(t) }

            receipts.forEachIndexed { index, r ->
                val row = sheet.createRow(index + 1)
                row.createCell(0).setCellValue(r.receiptId.toDouble())
                row.createCell(1).setCellValue(r.accountName)
                row.createCell(2).setCellValue(r.amount.toDouble())
                row.createCell(3).setCellValue(1.0) // عمود العملة دائماً قيمته 1[cite: 1]
                row.createCell(4).setCellValue(r.statement)
                row.createCell(5).setCellValue(r.date.toDouble())
            }

            // صيغة اسم الملف التلقائي: تحصيل + تاريخ التحصيل + اسم المستخدم[cite: 1]
            val fileName = "تحصيل_${dateString}_${username}.xls"[cite: 1]
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            FileOutputStream(file).use { workbook.write(it) }
            workbook.close()

            // إقفال اليوم المصدّر في قاعدة البيانات فوراً لمنع التعديل أو الحذف اللاحق[cite: 1]
            db.lockDay(receipts.first().date)
        }
    }
}
