# Node.js empacotado no APK (assets)

Coloque aqui **um** destes arquivos e o app instala o Node sozinho na primeira
abertura (`MainActivity.bootstrapRuntime()` → `NodeRuntime.installFromAssets()`):

```
app/src/main/assets/node/node-arm64-v8a.zip          # zip com bin/node + .so vizinhas
app/src/main/assets/node/node-arm64-v8a.tar.gz       # mesma coisa em tar.gz
app/src/main/assets/node/node                        # executável ELF Bionic solto
```

Regras:

1. O `node` precisa ser compilado para **Android/Bionic** (por exemplo, o
   binário de um Termux instalado no aparelho, copiado para dentro de um
   `.zip`/`.tar.gz` junto com as `.so` de que ele depende). O app **não** simula
   `apt`/`dpkg` nem baixa pacotes `.deb`: o foco é rodar projetos Node.js e
   `npm install`, e o npm cuida dos pacotes JS em `node_modules`.
2. Um `node` de Linux desktop (glibc) **não** funciona: o app lê o `PT_INTERP`
   do ELF e recusa o arquivo com uma mensagem clara. O Android usa
   `/system/bin/linker64`.
3. As dependências (`libc++_shared.so`, `libandroid-support.so`, `libssl`,
   `libcrypto`, `zlib`, ...) precisam estar no `.zip`/`.tar.gz`, na mesma pasta
   do `node` ou em `lib/`. O app copia as `.so` para `files/usr/lib` e ajusta o
   `RUNPATH` para `$ORIGIN/../lib`.
4. A configuração TLS (`openssl.cnf` e `cert.pem`) **não** vem do runtime: o app
   a extrai de `assets/runtime/` para `files/usr/etc/tls` e aponta
   `OPENSSL_CONF`/`SSL_CERT_FILE` para lá, evitando o erro de "Permission denied"
   no prefixo hardcoded do Termux.
5. Alternativa: `app/src/main/jniLibs/<abi>/libnode.so` com um **executável** ELF
   (ver `app/src/main/jniLibs/README.md`).

Sem nenhum arquivo aqui o app continua funcionando: o Node pode ser importado
em *Plugins → Node.js → Importar executável* ou baixado de uma URL.
