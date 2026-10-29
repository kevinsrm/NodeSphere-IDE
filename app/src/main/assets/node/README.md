# Node.js empacotado no APK (assets)

O aplicativo traz o binário nativo do Node.js para Android ARM64 (Bionic) embutido diretamente nesta pasta:

```
app/src/main/assets/node/node                        # executável ELF Bionic ARM64
```

Na primeira abertura do app (`MainActivity` → `BootstrapManager`), o sistema verifica se `files/usr/bin/node` existe. Se não existir, extrai automaticamente este executável, aplica as permissões de execução (`File(nodePath).setExecutable(true, false)`) e configura os lançadores e certificados TLS. O funcionamento é 100% automático (Plug and Play), sem necessidade de downloads manuais ou menus de importação.
