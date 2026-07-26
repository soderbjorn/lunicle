/**
 * The SQLite side of the store contract suite: a throwaway database on a temp
 * file, opened exactly the way the server opens the real one.
 *
 * Shared by every `SqlDelight*StoreContractTest`, because they all need the same
 * three things — a fresh database, the concrete stores built over it, and a clean
 * teardown that also removes SQLite's `-wal`/`-shm` companions — and one copy of
 * that plumbing is one place to fix it.
 *
 * `isPersistent = false` mirrors what the existing integration tests pass and is
 * the SQLite analog of the ticket's "in-memory/temp SQLite" contract fixture: the
 * file outlives nothing but the test.
 *
 * When the Firestore backend lands (LNL-111 Phase 3), its contract tests get a
 * parallel `FirestoreContractFixture` over the emulator; the abstract contracts
 * these fixtures feed do not change.
 */
package se.soderbjorn.lunicle.store

import java.io.File
import java.nio.file.Files
import se.soderbjorn.lunicle.DatabaseLocation
import se.soderbjorn.lunicle.db.LunicleDatabase
import se.soderbjorn.lunicle.openDatabase

/**
 * A temp SQLite database and the driver holding it, closed via [close].
 */
class SqlDelightContractFixture : AutoCloseable {
    private val file: File = Files.createTempFile("lunicle-contract", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "contract test"))

    /** The open database the stores under test are built over. */
    val database: LunicleDatabase get() = opened.database

    override fun close() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }
}
