# Node.js empacotado no APK (assets)

Coloque aqui **um** destes arquivos e o app instala o Node sozinho na primeira
abertura (`MainActivity.bootstrapRuntime()` → `NodeRuntime.installFromAssets()`):

```
app/src/main/assets/node/nodejs_24.9.1_aarch64.deb   # pacote do Termux (recomendado)
app/src/main/assets/node/node-arm64-v8a.zip          # zip com bin/node + .so vizinhas
app/src/main/assets/node/node-arm64-v8a.tar.gz       # mesma coisa em tar.gz
```

Regras:

1. O `.deb` precisa ser o do **Termux** (compilado contra Bionic). O instalador
   extrai o `data.tar.xz` e copia `data/data/com.termux/files/usr/*` para
   `files/usr/*`. Ele aceita também `.zip`/`.tar.gz` com `bin/node`.
2. Um `node` de Linux desktop (glibc) **não** funciona: o app lê o `PT_INTERP`
   do ELF e recusa o arquivo com uma mensagem clara. O Android usa
   `/system/bin/linker64`.
3. As dependências (`libc++_shared.so`, `libandroid-support.so`, `zlib`, ...)
   precisam estar no `.deb`/`.zip`. No caso do `.deb` do Termux elas vêm em
   pacotes separados — se o `node` reclamar de biblioteca ausente, use
   *Plugins → Node.js → Instalar do Termux*, que resolve a árvore inteira.
4. Alternativa: `app/src/main/jniLibs/<abi>/libnode.so` com um **executável** ELF
   (ver `app/src/main/jniLibs/README.md`).

Sem nenhum arquivo aqui o app continua funcionando: o Node pode ser instalado
em *Plugins → Node.js → Instalar do Termux* ou importado de um `.deb`.
