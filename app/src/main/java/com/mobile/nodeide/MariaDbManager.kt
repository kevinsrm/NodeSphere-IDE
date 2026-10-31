package com.mobile.nodeide

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Gerenciador do Daemon MariaDB / MySQL Local no NodeSphere.
 * Controla inicialização, ciclo de vida do servidor (porta 3306), diretório de dados em
 * `files/usr/var/mysql`, cliente CLI (`mysql -u root` / `mariadb`) e configuração de `.env`.
 */
class MariaDbManager(
    private val context: Context,
    val runtime: NodeRuntime
) {

    val dataDir: File get() = File(runtime.prefix, "var/mysql")
    val etcDir: File get() = File(runtime.prefix, "etc")
    val cnfFile: File get() = File(etcDir, "my.cnf")
    val logDir: File get() = File(runtime.prefix, "var/log")
    val logFile: File get() = File(logDir, "mysql.log")
    val pidFile: File get() = File(runtime.tmp, "mysqld.pid")
    val socketFile: File get() = File(runtime.tmp, "mysql.sock")
    val serverScript: File get() = File(runtime.prefix, "lib/mariadb/mariadb-server.js")
    val cliScript: File get() = File(runtime.prefix, "lib/mariadb/mariadb-cli.js")

    private var daemonProcess: Process? = null

    /**
     * Prepara diretórios, arquivo de configuração `my.cnf` e scripts de execução.
     */
    fun prepare() {
        runtime.prepare()
        dataDir.mkdirs()
        etcDir.mkdirs()
        logDir.mkdirs()
        writeConfigFilesIfNeeded()
        writeScriptsIfNeeded()
    }

    /**
     * Cria o arquivo de configuração padrão `my.cnf`.
     */
    private fun writeConfigFilesIfNeeded() {
        if (!cnfFile.isFile || cnfFile.length() == 0L) {
            val content = buildString {
                append("[client]\n")
                append("port = 3306\n")
                append("socket = ${socketFile.absolutePath}\n")
                append("user = root\n\n")
                append("[mysqld]\n")
                append("user = root\n")
                append("port = 3306\n")
                append("bind-address = 127.0.0.1\n")
                append("socket = ${socketFile.absolutePath}\n")
                append("datadir = ${dataDir.absolutePath}\n")
                append("tmpdir = ${runtime.tmp.absolutePath}\n")
                append("log-error = ${logFile.absolutePath}\n")
                append("pid-file = ${pidFile.absolutePath}\n")
                append("skip-networking = 0\n")
            }
            try {
                MainActivity.write(cnfFile, content)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao gravar my.cnf: ${e.message}")
            }
        }
    }

    /**
     * Grava os scripts JavaScript do servidor e cliente MariaDB/MySQL.
     */
    fun writeScriptsIfNeeded() {
        val libMariaDir = File(runtime.prefix, "lib/mariadb")
        libMariaDir.mkdirs()

        // 1. mariadb-server.js
        if (!serverScript.isFile || serverScript.length() < 100L) {
            try {
                MainActivity.write(serverScript, SERVER_SCRIPT_SOURCE.trimIndent().trimStart())
                serverScript.setExecutable(true, false)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao gravar mariadb-server.js: ${e.message}")
            }
        }

        // 2. mariadb-cli.js
        if (!cliScript.isFile || cliScript.length() < 100L) {
            try {
                MainActivity.write(cliScript, CLI_SCRIPT_SOURCE.trimIndent().trimStart())
                cliScript.setExecutable(true, false)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao gravar mariadb-cli.js: ${e.message}")
            }
        }
    }

    /**
     * Verifica se o servidor MariaDB/MySQL está respondendo na porta 3306.
     */
    fun isRunning(port: Int = 3306): Boolean {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 400)
                return true
            }
        } catch (ignored: Exception) {
            return false
        }
    }

    /**
     * Inicializa a estrutura de banco de dados no diretório `files/usr/var/mysql`.
     */
    fun initDatabase(force: Boolean = false): Boolean {
        prepare()
        if (force) {
            dataDir.deleteRecursively()
            dataDir.mkdirs()
        }
        val defaultDb = File(dataDir, "nodesphere_db.db")
        if (!defaultDb.isFile) {
            try {
                val nodeBin = runtime.nodeFile
                if (nodeBin.isFile) {
                    val script = "const fs = require('fs'); const { DatabaseSync } = require('node:sqlite'); " +
                        "const db = new DatabaseSync('${defaultDb.absolutePath}'); " +
                        "db.exec('CREATE TABLE IF NOT EXISTS _nodesphere_info (key TEXT PRIMARY KEY, value TEXT);'); " +
                        "db.exec(\"INSERT OR REPLACE INTO _nodesphere_info (key, value) VALUES ('version', '10.11.8-MariaDB-NodeSphere');\"); " +
                        "db.close();"
                    val builder = ProcessBuilder(nodeBin.absolutePath, "-e", script)
                    builder.environment().putAll(runtime.environment())
                    builder.redirectErrorStream(true)
                    val process = builder.start()
                    process.waitFor()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao inicializar defaultDb com SQLite: ${e.message}")
            }
        }
        return dataDir.isDirectory
    }

    /**
     * Inicia o daemon do MariaDB em segundo plano na porta especificada (padrão 3306).
     */
    @Synchronized
    fun startDaemon(port: Int = 3306, onLog: ((String) -> Unit)? = null): Boolean {
        prepare()
        initDatabase(force = false)

        if (isRunning(port)) {
            onLog?.invoke("Servidor MariaDB/MySQL já está em execução na porta $port.")
            return true
        }

        val nodeBin = runtime.nodeFile
        if (!nodeBin.isFile) {
            onLog?.invoke("Erro: Executável do Node.js não encontrado em ${nodeBin.absolutePath}.")
            return false
        }

        try {
            val command = listOf(
                nodeBin.absolutePath,
                serverScript.absolutePath,
                "--port=$port",
                "--bind-address=127.0.0.1",
                "--datadir=${dataDir.absolutePath}",
                "--pid-file=${pidFile.absolutePath}",
                "--log-error=${logFile.absolutePath}"
            )

            val builder = ProcessBuilder(command)
            builder.directory(runtime.home)
            builder.environment().putAll(runtime.environment())
            builder.redirectErrorStream(true)

            val process = builder.start()
            daemonProcess = process

            Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[mysqld] $line")
                            onLog?.invoke(line)
                        }
                    }
                } catch (ignored: Exception) {
                }
            }, "mariadb-daemon-logger").start()

            // Aguarda até 3 segundos pela inicialização
            for (i in 1..15) {
                Thread.sleep(200)
                if (isRunning(port)) {
                    onLog?.invoke("MariaDB daemon iniciado com sucesso em 127.0.0.1:$port")
                    return true
                }
            }
            return isRunning(port)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar MariaDB daemon: ${e.message}", e)
            onLog?.invoke("Falha ao iniciar: ${e.message}")
            return false
        }
    }

    /**
     * Encerra o daemon do MariaDB.
     */
    @Synchronized
    fun stopDaemon(): Boolean {
        try {
            daemonProcess?.destroy()
            daemonProcess = null

            if (pidFile.isFile) {
                try {
                    val pid = pidFile.readText().trim().toIntOrNull()
                    if (pid != null && pid > 0) {
                        android.os.Process.killProcess(pid)
                        try { android.os.Process.sendSignal(pid, 9) } catch (ignored: Exception) {}
                    }
                } catch (ignored: Exception) {
                }
                pidFile.delete()
            }
            TerminalSession.killAllChildProcesses()
            return !isRunning()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao encerrar MariaDB daemon: ${e.message}")
            return false
        }
    }

    /**
     * Cria ou atualiza o arquivo `.env` dentro da pasta do projeto com as credenciais padrão do MariaDB/MySQL.
     */
    fun configureProjectEnv(projectDir: File, dbName: String = "nodesphere_db", port: Int = 3306): File {
        if (!projectDir.exists()) projectDir.mkdirs()
        val envFile = File(projectDir, ".env")
        val configSnippet = buildString {
            append("\n# MariaDB / MySQL Local (NodeSphere Embedded)\n")
            append("DB_HOST=127.0.0.1\n")
            append("DB_PORT=$port\n")
            append("DB_USER=root\n")
            append("DB_PASSWORD=\n")
            append("DB_NAME=$dbName\n")
            append("DATABASE_URL=\"mysql://root:@127.0.0.1:$port/$dbName\"\n")
        }

        if (envFile.isFile) {
            val existing = try { envFile.readText() } catch (ignored: Exception) { "" }
            if (!existing.contains("DB_HOST")) {
                envFile.appendText(configSnippet)
            }
        } else {
            MainActivity.write(envFile, configSnippet.trimStart())
        }
        return envFile
    }

    /**
     * Retorna a string modelo do arquivo `.env` para MariaDB/MySQL.
     */
    fun getEnvTemplate(dbName: String = "nodesphere_db", port: Int = 3306): String =
        """
        # Configuração MariaDB / MySQL Local (NodeSphere)
        DB_HOST=127.0.0.1
        DB_PORT=$port
        DB_USER=root
        DB_PASSWORD=
        DB_NAME=$dbName
        DATABASE_URL="mysql://root:@127.0.0.1:$port/$dbName"
        """.trimIndent()

    companion object {
        private const val TAG = "MariaDbManager"

        private val SERVER_SCRIPT_SOURCE = """
            #!/usr/bin/env node
            /**
             * MariaDB / MySQL Server Daemon (NodeSphere Embedded)
             * Protocol 10 wire server on TCP 127.0.0.1:3306
             * Backed by SQLite (node:sqlite) with persistent storage in files/usr/var/mysql/
             */
            const net = require('net');
            const fs = require('fs');
            const path = require('path');
            const { DatabaseSync } = require('node:sqlite');

            let port = 3306;
            let host = '127.0.0.1';
            let datadir = path.join(process.env.PREFIX || '/data/user/0/com.mobile.nodeide/files/usr', 'var', 'mysql');
            let pidFile = path.join(process.env.TMPDIR || '/data/user/0/com.mobile.nodeide/files/usr/tmp', 'mysqld.pid');

            for (const arg of process.argv.slice(2)) {
              if (arg.startsWith('--port=')) port = parseInt(arg.split('=')[1], 10) || 3306;
              if (arg.startsWith('--bind-address=')) host = arg.split('=')[1];
              if (arg.startsWith('--datadir=')) datadir = arg.split('=')[1];
              if (arg.startsWith('--pid-file=')) pidFile = arg.split('=')[1];
            }

            fs.mkdirSync(datadir, { recursive: true });
            try { fs.writeFileSync(pidFile, String(process.pid)); } catch (e) {}

            const databases = new Map();

            function getDatabase(name) {
              const cleanName = (name || 'nodesphere_db').replace(/[^a-zA-Z0-9_-]/g, '_');
              if (databases.has(cleanName)) return databases.get(cleanName);
              const dbFile = path.join(datadir, cleanName + '.db');
              const db = new DatabaseSync(dbFile);
              db.exec('PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL;');
              databases.set(cleanName, db);
              return db;
            }

            function listDatabases() {
              const files = fs.readdirSync(datadir);
              const names = new Set(['information_schema', 'mysql', 'performance_schema', 'sys', 'nodesphere_db', 'test']);
              for (const f of files) {
                if (f.endsWith('.db')) names.add(f.slice(0, -3));
              }
              return Array.from(names);
            }

            // Packet helpers
            function writeLenEncInt(val) {
              if (val < 251) {
                const b = Buffer.alloc(1);
                b[0] = val;
                return b;
              } else if (val <= 0xffff) {
                const b = Buffer.alloc(3);
                b[0] = 0xfc;
                b.writeUInt16LE(val, 1);
                return b;
              } else if (val <= 0xffffff) {
                const b = Buffer.alloc(4);
                b[0] = 0xfd;
                b.writeUIntLE(val, 1, 3);
                return b;
              } else {
                const b = Buffer.alloc(9);
                b[0] = 0xfe;
                b.writeBigUInt64LE(BigInt(val), 1);
                return b;
              }
            }

            function writeLenEncStr(str) {
              if (str === null || str === undefined) return Buffer.from([0xfb]);
              const sBuf = Buffer.from(String(str), 'utf8');
              return Buffer.concat([writeLenEncInt(sBuf.length), sBuf]);
            }

            function sendPacket(socket, payload, seq) {
              const len = payload.length;
              const header = Buffer.alloc(4);
              header[0] = len & 0xff;
              header[1] = (len >> 8) & 0xff;
              header[2] = (len >> 16) & 0xff;
              header[3] = seq & 0xff;
              socket.write(Buffer.concat([header, payload]));
            }

            function sendOk(socket, seq, affected = 0, lastId = 0) {
              const p = Buffer.alloc(7);
              p[0] = 0x00;
              p[1] = affected & 0xff;
              p[2] = lastId & 0xff;
              p[3] = 0x02; // autocommit
              p[4] = 0x00;
              p[5] = 0x00;
              p[6] = 0x00;
              sendPacket(socket, p, seq);
            }

            function sendError(socket, seq, message, code = 1064) {
              const msgBuf = Buffer.from(message || 'Syntax error', 'utf8');
              const p = Buffer.alloc(9 + msgBuf.length);
              p[0] = 0xff;
              p.writeUInt16LE(code, 1);
              p[3] = 0x23; // '#'
              p.write('HY000', 4, 5, 'ascii');
              msgBuf.copy(p, 9);
              sendPacket(socket, p, seq);
            }

            function createColumnDef(name, table = '', type = 0xfd) {
              return Buffer.concat([
                writeLenEncStr('def'),
                writeLenEncStr(''),
                writeLenEncStr(table),
                writeLenEncStr(table),
                writeLenEncStr(name),
                writeLenEncStr(name),
                Buffer.from([0x0c, 0x21, 0x00, 0xff, 0xff, 0x00, 0x00, type, 0x00, 0x00, 0x00, 0x00, 0x00])
              ]);
            }

            function sendResultSet(socket, seqRef, columns, rows, currentDbName) {
              let seq = seqRef.val;
              // 1. Column count packet
              sendPacket(socket, writeLenEncInt(columns.length), seq++);
              // 2. Column definition packets
              for (const col of columns) {
                sendPacket(socket, createColumnDef(col), seq++);
              }
              // 3. EOF packet
              sendPacket(socket, Buffer.from([0xfe, 0x00, 0x00, 0x02, 0x00]), seq++);
              // 4. Row packets
              for (const row of rows) {
                const parts = [];
                for (let i = 0; i < columns.length; i++) {
                  const val = row[columns[i]] !== undefined ? row[columns[i]] : (Array.isArray(row) ? row[i] : null);
                  parts.push(writeLenEncStr(val));
                }
                sendPacket(socket, Buffer.concat(parts), seq++);
              }
              // 5. EOF packet
              sendPacket(socket, Buffer.from([0xfe, 0x00, 0x00, 0x02, 0x00]), seq++);
              seqRef.val = seq;
            }

            let connectionCounter = 1;

            const server = net.createServer((socket) => {
              const connId = connectionCounter++;
              let seq = 0;
              let authenticated = false;
              let currentDb = 'nodesphere_db';
              const salt = Buffer.from('nodespheresalt12345678', 'ascii');

              // Handshake v10
              const version = Buffer.from('10.11.8-MariaDB-NodeSphere\0', 'ascii');
              const hsParts = [
                Buffer.from([0x0a]),
                version,
                Buffer.alloc(4),
                salt.slice(0, 8),
                Buffer.from([0x00]),
                Buffer.from([0xf7, 0xbf]), // capabilities lower
                Buffer.from([0x21]), // utf8_general_ci
                Buffer.from([0x02, 0x00]), // status flags autocommit
                Buffer.from([0x81, 0x00]), // capabilities upper
                Buffer.from([0x15]), // auth data len
                Buffer.alloc(10), // reserved
                salt.slice(8, 20),
                Buffer.from([0x00]),
                Buffer.from('mysql_native_password\0', 'ascii')
              ];
              hsParts[2].writeUInt32LE(connId, 0);
              sendPacket(socket, Buffer.concat(hsParts), seq++);

              let buffer = Buffer.alloc(0);

              socket.on('data', (chunk) => {
                buffer = Buffer.concat([buffer, chunk]);
                while (buffer.length >= 4) {
                  const packetLen = buffer[0] | (buffer[1] << 8) | (buffer[2] << 16);
                  if (buffer.length < 4 + packetLen) break;
                  const packetSeq = buffer[3];
                  const payload = buffer.slice(4, 4 + packetLen);
                  buffer = buffer.slice(4 + packetLen);

                  let responseSeq = (packetSeq + 1) & 0xff;

                  if (!authenticated) {
                    authenticated = true;
                    sendOk(socket, responseSeq);
                    continue;
                  }

                  const cmd = payload[0];
                  if (cmd === 0x01) { // COM_QUIT
                    socket.end();
                    return;
                  }
                  if (cmd === 0x0e) { // COM_PING
                    sendOk(socket, responseSeq);
                    continue;
                  }
                  if (cmd === 0x02) { // COM_INIT_DB
                    currentDb = payload.slice(1).toString('utf8').trim();
                    getDatabase(currentDb);
                    sendOk(socket, responseSeq);
                    continue;
                  }
                  if (cmd === 0x03) { // COM_QUERY
                    const rawSql = payload.slice(1).toString('utf8').trim();
                    const sql = rawSql.replace(/;\s*${'$'}/, '').trim();
                    const upper = sql.toUpperCase();
                    const seqRef = { val: responseSeq };

                    try {
                      if (upper === 'SELECT @@VERSION' || upper.startsWith('SELECT @@VERSION')) {
                        sendResultSet(socket, seqRef, ['@@version'], [{ '@@version': '10.11.8-MariaDB-NodeSphere' }], currentDb);
                        continue;
                      }
                      if (upper.startsWith('SELECT DATABASE()')) {
                        sendResultSet(socket, seqRef, ['DATABASE()'], [{ 'DATABASE()': currentDb }], currentDb);
                        continue;
                      }
                      if (upper.startsWith('SHOW DATABASES')) {
                        const dbs = listDatabases();
                        sendResultSet(socket, seqRef, ['Database'], dbs.map(d => ({ Database: d })), currentDb);
                        continue;
                      }
                      if (upper.startsWith('CREATE DATABASE')) {
                        const match = sql.match(/CREATE\s+DATABASE\s+(?:IF\s+NOT\s+EXISTS\s+)?([`a-zA-Z0-9_-]+)/i);
                        if (match) {
                          const name = match[1].replace(/`/g, '');
                          getDatabase(name);
                          sendOk(socket, responseSeq, 1);
                        } else {
                          sendOk(socket, responseSeq, 1);
                        }
                        continue;
                      }
                      if (upper.startsWith('DROP DATABASE')) {
                        const match = sql.match(/DROP\s+DATABASE\s+(?:IF\s+EXISTS\s+)?([`a-zA-Z0-9_-]+)/i);
                        if (match) {
                          const name = match[1].replace(/`/g, '');
                          const dbFile = path.join(datadir, name + '.db');
                          if (databases.has(name)) {
                            databases.get(name).close();
                            databases.delete(name);
                          }
                          try { fs.unlinkSync(dbFile); } catch (e) {}
                        }
                        sendOk(socket, responseSeq, 1);
                        continue;
                      }
                      if (upper.startsWith('USE ')) {
                        const name = sql.slice(4).trim().replace(/[`"';]/g, '');
                        currentDb = name;
                        getDatabase(currentDb);
                        sendOk(socket, responseSeq);
                        continue;
                      }
                      if (upper.startsWith('SHOW TABLES')) {
                        const db = getDatabase(currentDb);
                        const stmt = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;");
                        const rows = stmt.all();
                        const key = `Tables_in_${'$'}{currentDb}`;
                        sendResultSet(socket, seqRef, [key], rows.map(r => ({ [key]: r.name })), currentDb);
                        continue;
                      }
                      if (upper.startsWith('SHOW COLUMNS FROM ') || upper.startsWith('DESCRIBE ') || upper.startsWith('DESC ')) {
                        const table = sql.replace(/^(SHOW\s+COLUMNS\s+FROM|DESCRIBE|DESC)\s+/i, '').replace(/[`"';]/g, '').trim();
                        const db = getDatabase(currentDb);
                        const stmt = db.prepare(`PRAGMA table_info("${'$'}{table}");`);
                        const rows = stmt.all();
                        const formatted = rows.map(r => ({
                          Field: r.name,
                          Type: r.type || 'text',
                          Null: r.notnull ? 'NO' : 'YES',
                          Key: r.pk ? 'PRI' : '',
                          Default: r.dflt_value,
                          Extra: r.pk ? 'auto_increment' : ''
                        }));
                        sendResultSet(socket, seqRef, ['Field', 'Type', 'Null', 'Key', 'Default', 'Extra'], formatted, currentDb);
                        continue;
                      }
                      if (upper.startsWith('SET ') || upper.startsWith('SHOW VARIABLES') || upper.startsWith('SHOW STATUS') || upper.startsWith('SHOW COLLATION') || upper.startsWith('SHOW CHARACTER SET')) {
                        if (upper.startsWith('SHOW VARIABLES') || upper.startsWith('SHOW STATUS')) {
                          sendResultSet(socket, seqRef, ['Variable_name', 'Value'], [
                            { Variable_name: 'version', Value: '10.11.8-MariaDB-NodeSphere' },
                            { Variable_name: 'port', Value: String(port) },
                            { Variable_name: 'character_set_client', Value: 'utf8mb4' },
                            { Variable_name: 'character_set_connection', Value: 'utf8mb4' },
                            { Variable_name: 'character_set_results', Value: 'utf8mb4' },
                            { Variable_name: 'character_set_server', Value: 'utf8mb4' },
                            { Variable_name: 'collation_connection', Value: 'utf8mb4_general_ci' },
                            { Variable_name: 'max_connections', Value: '100' }
                          ], currentDb);
                        } else {
                          sendOk(socket, responseSeq);
                        }
                        continue;
                      }

                      // Execute general query with SQLite engine
                      const db = getDatabase(currentDb);
                      if (upper.startsWith('SELECT') || upper.startsWith('PRAGMA') || upper.startsWith('EXPLAIN') || upper.startsWith('WITH')) {
                        const stmt = db.prepare(sql);
                        const rows = stmt.all();
                        const columns = rows.length > 0 ? Object.keys(rows[0]) : [];
                        sendResultSet(socket, seqRef, columns, rows, currentDb);
                      } else {
                        // Normalize MySQL dialect to SQLite (AUTO_INCREMENT, backticks)
                        const normalizedSql = sql
                          .replace(/AUTO_INCREMENT/gi, 'AUTOINCREMENT')
                          .replace(/ENGINE\s*=\s*[a-zA-Z0-9_]+/gi, '')
                          .replace(/DEFAULT\s+CHARSET\s*=\s*[a-zA-Z0-9_]+/gi, '');
                        db.exec(normalizedSql);
                        sendOk(socket, responseSeq, 1);
                      }
                    } catch (err) {
                      sendError(socket, responseSeq, err.message);
                    }
                  }
                }
              });

              socket.on('error', () => {});
            });

            server.listen(port, host, () => {
              console.log(`MariaDB / MySQL Local Server listening on ${'$'}{host}:${'$'}{port} (datadir: ${'$'}{datadir})`);
            });
        """.trimIndent()

        private val CLI_SCRIPT_SOURCE = """
            #!/usr/bin/env node
            /**
             * MariaDB / MySQL Interactive CLI Monitor (NodeSphere Embedded)
             */
            const net = require('net');
            const readline = require('readline');

            let host = '127.0.0.1';
            let port = 3306;
            let user = 'root';
            let executeSql = null;
            let initialDb = null;

            const args = process.argv.slice(2);
            for (let i = 0; i < args.length; i++) {
              const a = args[i];
              if (a === '-h' && args[i + 1]) host = args[++i];
              else if (a === '-P' && args[i + 1]) port = parseInt(args[++i], 10) || 3306;
              else if (a === '-u' && args[i + 1]) user = args[++i];
              else if (a === '-e' && args[i + 1]) executeSql = args[++i];
              else if (!a.startsWith('-')) initialDb = a;
            }

            console.log('Welcome to the MariaDB monitor (NodeSphere Embedded).  Commands end with ; or \g.');
            console.log('Your MariaDB connection id is 1');
            console.log('Server version: 10.11.8-MariaDB-NodeSphere Source distribution\n');
            console.log('Copyright (c) 2000, 2018, Oracle, MariaDB Corporation Ab and others.\n');
            console.log("Type 'help;' or '\\h' for help. Type '\\c' to clear the current input statement.\n");

            let currentDb = initialDb || '(none)';

            // Native SQLite fallback for direct CLI interaction
            const path = require('path');
            const fs = require('fs');
            const { DatabaseSync } = require('node:sqlite');
            const datadir = path.join(process.env.PREFIX || '/data/user/0/com.mobile.nodeide/files/usr', 'var', 'mysql');
            fs.mkdirSync(datadir, { recursive: true });

            function getDb(name) {
              const clean = (name === '(none)' ? 'nodesphere_db' : name).replace(/[^a-zA-Z0-9_-]/g, '_');
              const dbFile = path.join(datadir, clean + '.db');
              const db = new DatabaseSync(dbFile);
              db.exec('PRAGMA foreign_keys = ON;');
              return db;
            }

            function formatTable(columns, rows) {
              if (columns.length === 0) return 'Empty set\n';
              const colWidths = columns.map(c => c.length);
              for (const r of rows) {
                columns.forEach((c, i) => {
                  const val = r[c] !== null && r[c] !== undefined ? String(r[c]) : 'NULL';
                  if (val.length > colWidths[i]) colWidths[i] = val.length;
                });
              }
              const border = '+' + colWidths.map(w => '-'.repeat(w + 2)).join('+') + '+';
              const header = '| ' + columns.map((c, i) => c.padEnd(colWidths[i])).join(' | ') + ' |';
              const lines = [border, header, border];
              for (const r of rows) {
                const rowStr = '| ' + columns.map((c, i) => {
                  const val = r[c] !== null && r[c] !== undefined ? String(r[c]) : 'NULL';
                  return val.padEnd(colWidths[i]);
                }).join(' | ') + ' |';
                lines.push(rowStr);
              }
              lines.push(border);
              lines.push(`${'$'}{rows.length} row${'$'}{rows.length === 1 ? '' : 's'} in set\n`);
              return lines.join('\n');
            }

            function runSql(raw) {
              const sql = raw.trim().replace(/;\s*${'$'}/, '');
              if (!sql) return;
              const upper = sql.toUpperCase();
              try {
                if (upper.startsWith('USE ')) {
                  currentDb = sql.slice(4).trim().replace(/[`"';]/g, '');
                  console.log(`Database changed to ${'$'}{currentDb}`);
                  return;
                }
                if (upper.startsWith('SHOW DATABASES')) {
                  const files = fs.readdirSync(datadir);
                  const names = new Set(['information_schema', 'mysql', 'performance_schema', 'sys', 'nodesphere_db', 'test']);
                  for (const f of files) if (f.endsWith('.db')) names.add(f.slice(0, -3));
                  const rows = Array.from(names).map(d => ({ Database: d }));
                  console.log(formatTable(['Database'], rows));
                  return;
                }
                if (upper.startsWith('SHOW TABLES')) {
                  const db = getDb(currentDb);
                  const stmt = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;");
                  const rows = stmt.all();
                  const key = `Tables_in_${'$'}{currentDb}`;
                  console.log(formatTable([key], rows.map(r => ({ [key]: r.name }))));
                  return;
                }
                if (upper.startsWith('SHOW COLUMNS FROM ') || upper.startsWith('DESCRIBE ') || upper.startsWith('DESC ')) {
                  const table = sql.replace(/^(SHOW\s+COLUMNS\s+FROM|DESCRIBE|DESC)\s+/i, '').replace(/[`"';]/g, '').trim();
                  const db = getDb(currentDb);
                  const stmt = db.prepare(`PRAGMA table_info("${'$'}{table}");`);
                  const rows = stmt.all().map(r => ({
                    Field: r.name,
                    Type: r.type || 'text',
                    Null: r.notnull ? 'NO' : 'YES',
                    Key: r.pk ? 'PRI' : '',
                    Default: r.dflt_value,
                    Extra: r.pk ? 'auto_increment' : ''
                  }));
                  console.log(formatTable(['Field', 'Type', 'Null', 'Key', 'Default', 'Extra'], rows));
                  return;
                }
                const db = getDb(currentDb);
                if (upper.startsWith('SELECT') || upper.startsWith('PRAGMA') || upper.startsWith('EXPLAIN')) {
                  const stmt = db.prepare(sql);
                  const rows = stmt.all();
                  const cols = rows.length > 0 ? Object.keys(rows[0]) : [];
                  console.log(formatTable(cols, rows));
                } else {
                  const normalized = sql
                    .replace(/AUTO_INCREMENT/gi, 'AUTOINCREMENT')
                    .replace(/ENGINE\s*=\s*[a-zA-Z0-9_]+/gi, '')
                    .replace(/DEFAULT\s+CHARSET\s*=\s*[a-zA-Z0-9_]+/gi, '');
                  db.exec(normalized);
                  console.log('Query OK, statement executed successfully.\n');
                }
              } catch (e) {
                console.log(`ERROR: ${'$'}{e.message}\n`);
              }
            }

            if (executeSql) {
              runSql(executeSql);
              process.exit(0);
            }

            const rl = readline.createInterface({
              input: process.stdin,
              output: process.stdout,
              prompt: `mariadb [${'$'}{currentDb}]> `
            });

            let multiline = '';

            rl.prompt();
            rl.on('line', (line) => {
              const trimmed = line.trim();
              if (trimmed === 'exit' || trimmed === 'quit' || trimmed === '\\q') {
                rl.close();
                return;
              }
              if (trimmed === '\\c') {
                multiline = '';
                rl.setPrompt(`mariadb [${'$'}{currentDb}]> `);
                rl.prompt();
                return;
              }
              if (multiline) multiline += ' ' + line;
              else multiline = line;

              if (multiline.trim().endsWith(';') || multiline.trim().endsWith('\\g') || multiline.trim().endsWith('\\G')) {
                runSql(multiline);
                multiline = '';
                rl.setPrompt(`mariadb [${'$'}{currentDb}]> `);
              } else {
                rl.setPrompt('    -> ');
              }
              rl.prompt();
            }).on('close', () => {
              console.log('Bye');
              process.exit(0);
            });
        """.trimIndent()
    }
}
