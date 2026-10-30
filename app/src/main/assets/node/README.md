# Node.js e Bibliotecas Compartilhadas no APK (assets)

O aplicativo traz o executável nativo do Node.js e o conjunto de bibliotecas essenciais do toolchain Bionic/Android ARM64 embutidos diretamente nesta pasta:

```
app/src/main/assets/node/node                  # Executável ELF Bionic ARM64
app/src/main/assets/node/libz.so.1             # Biblioteca de compressão zlib
app/src/main/assets/node/libz.so               # Alias/link para libz
app/src/main/assets/node/libc++_shared.so      # C++ Runtime (LLVM libc++)
app/src/main/assets/node/libcares.so           # Resolução DNS assíncrona (c-ares)
app/src/main/assets/node/libcrypto.so.3        # Criptografia OpenSSL 3
app/src/main/assets/node/libssl.so.3           # TLS/SSL OpenSSL 3
app/src/main/assets/node/libsqlite3.so         # SQLite 3
app/src/main/assets/node/libicudata.so.78      # Tabelas de internacionalização ICU 78
app/src/main/assets/node/libicui18n.so.78      # Internacionalização ICU 78
app/src/main/assets/node/libicuuc.so.78        # Funções comuns ICU 78
app/src/main/assets/node/libuv.so              # Event Loop libuv
```

Na primeira abertura do app (`MainActivity` → `BootstrapManager`), o sistema realiza o bootstrap automático:
1. Extrai `node` para `files/usr/bin/node` e aplica permissão de execução (`File.setExecutable(true, false)`).
2. Extrai todas as bibliotecas `.so` para o diretório `files/usr/lib/`, garantindo os aliases e permissões de execução.
3. Injeta `LD_LIBRARY_PATH` (`files/usr/lib:<nativeLibraryDir>`) e o ambiente TLS (`OPENSSL_CONF`, `SSL_CERT_FILE`) em todos os processos via `ProcessBuilder`.
4. O funcionamento é 100% automático (Plug and Play), sem necessidade de downloads manuais ou menus de importação.
