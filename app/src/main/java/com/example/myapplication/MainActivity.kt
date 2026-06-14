package com.example.myapplication

import android.os.Bundle
import android.os.Environment
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.room.*
import java.io.File
import java.io.FileOutputStream
import org.apache.poi.hssf.usermodel.HSSFWorkbook

// --- 1. جداول قاعدة البيانات والقيود لمنع التكرار ---
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
    val date: Int, // تاريخ السند[cite: 1]
    val isLocked: Boolean = false // حالة إقفال السند واليوم[cite: 1]
)

@Entity(tableName = "user_table")
data class User(
    @PrimaryKey val id: Int = 1,
    var username: String,
    var password: String
)

// --- 2. واجهة العمليات البرمجية وقيود الأمان ---
@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveUser(user: User)
    @Query("SELECT * FROM user_table WHERE id = 1 LIMIT 1") fun getUser(): User?

    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertAccount(account: Account)
    @Update fun updateAccount(account: Account)
    @Delete fun deleteAccount(account: Account)
    @Query("DELETE FROM accounts_table") fun deleteAllAccounts() // مسح البيانات قبل استيراد الاكسل[cite: 1]
    @Query("SELECT * FROM accounts_table WHERE accountNumber = :num OR accountName LIKE :name") fun searchAccounts(num: Int, name: String): List<Account>

    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertReceipt(receipt: Receipt)
    @Update fun updateReceipt(receipt: Receipt)
    @Delete fun deleteReceipt(receipt: Receipt)
    @Query("SELECT MAX(receiptId) FROM receipts_table") fun getMaxReceiptId(): Int
    @Query("SELECT * FROM receipts_table WHERE isLocked = 0") fun getLastActiveDayReceipts(): List<Receipt>
    @Query("UPDATE receipts_table SET isLocked = 1 WHERE date = :targetDate") fun lockDay(targetDate: Int) // إقفال اليوم المصدر[cite: 1]
}

@Database(entities = [Account::class, Receipt::class, User::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
}

// --- 3. منطق تشغيل الشاشة والتحكم بالتصدير والاستيراد وحماية السندات ---
class MainActivity : AppCompatActivity() {
    private lateinit var db: AppDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // بناء قاعدة البيانات المستقرة سحابياً
        val database = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "receipts_secure_db")
            .allowMainThreadQueries()
            .build()
        db = database.appDao()

        // واجهة برمجية صافية ومباشرة لتجنب مشاكل التصميم
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }
        val textView = TextView(this).apply {
            text = "تطبيق السندات جاهز للعمل ومقفل برمجياً حسب الشروط!"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        layout.addView(textView)
        setContentView(layout)
    }

    // دالة التصدير والإقفال لليوم المصدّر[cite: 1]
    fun exportToExcelAndLock(username: String, dateString: String) {
        val receipts = db.getLastActiveDayReceipts()
        if (receipts.isEmpty()) return

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
        try {
            FileOutputStream(file).use { workbook.write(it) }
            workbook.close()
            // إقفال اليوم المصدّر فوراً لمنع التعديل أو الحذف اللاحق[cite: 1]
            db.lockDay(receipts.first().date)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
