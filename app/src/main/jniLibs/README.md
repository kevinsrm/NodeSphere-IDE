# Node.js empacotado no APK

Este diretório é o único lugar do aplicativo onde o Android permite **executar**
um binário gravado pelo próprio app (`nativeLibraryDir`). Para o NodeSphere
instalar o Node.js sozinho na primeira abertura, coloque um executável de Node
compilado para Android com o nome `libnode.so`, uma pasta por ABI:

```
app/src/main/jniLibs/arm64-v8a/libnode.so     # celulares modernos
app/src/main/jniLibs/armeabi-v7a/libnode.so   # 32 bits
app/src/main/jniLibs/x86_64/libnode.so        # emuladores
```

Regras:

1. O arquivo precisa ser um **executável** ELF (`ET_EXEC`/`ET_DYN` com
   `PT_INTERP` apontando para `/system/bin/linker64`). Um `libnode.so`
   compartilhado (sem interpretador) é recusado — ele não tem `main`.
2. `android:extractNativeLibs="true"` (já no manifesto) e
   `packaging { jniLibs { useLegacyPackaging = true } }` (já no Gradle) fazem o
   arquivo chegar como arquivo real em `nativeLibraryDir`.
3. Na primeira execução, `NodeRuntime.installBundled()` copia o binário para
   `files/usr/bin/node`, ajusta o `RUNPATH`, cria os atalhos `npm`/`npx` e baixa
   o npm do registry.
4. Sem esse arquivo, o app continua funcionando: o Node pode ser importado em
   *Plugins → Node.js → Importar executável* (`.zip`, `.tar.gz` ou `node`
   Bionic), baixado de uma URL ou empacotado em `app/src/main/assets/node/`
   (ver o README daquela pasta). O app **não** simula `apt`/`dpkg`.
5. Um `node` de Linux desktop (glibc) é recusado: `installBinary()` lê o
   `PT_INTERP` e exige `/system/bin/linker64` (Bionic).

Nada aqui é obrigatório para compilar; o diretório existe para documentar o
caminho de empacotamento e evitar que a pasta seja removida.
