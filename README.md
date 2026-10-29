# NodeSphere IDE

Protótipo Android nativo escrito **100% em Kotlin**, com XML, Fragments e RecyclerView, inspirado em IDEs mobile. Usa `compileSdk = 36`, `targetSdk = 28` (ver [por que](#por-que-targetsdk-28)), `minSdk = 26` e o pacote `com.mobile.nodeide`.

## Linguagem: Kotlin

Todo o código-fonte é Kotlin; não há arquivos `.java` no projeto.

| Arquivo | Papel |
| --- | --- |
| `MainActivity.kt` | Activity única, navegação inferior, insets e *bootstrap* do runtime |
| `ProjectsFragment.kt` | Criação, importação (SAF) e clonagem de projetos |
| `IdeFragment.kt` | Editor: árvore, abas, símbolos, undo/redo e terminal integrado |
| `CodeEditor.kt` | `AppCompatEditText` com números de linha, realce lexical e autocompletar |
| `TerminalEmulator.kt` | Emulador de terminal em Kotlin puro: grade de caracteres, cursor, ANSI/SGR, scrollback |
| `TerminalView.kt` | `ScrollView` + `TextView` monoespaçado com seleção/cópia nativas e teclado na própria tela |
| `TerminalSession.kt` | Shell `/system/bin/sh` na pasta do projeto: eco, edição de linha, histórico, `cwd`, `^C`/`^D` |
| `NodeRuntime.kt` | Prefixo `usr/`, ambiente do shell (incluindo `OPENSSL_CONF`/`SSL_CERT_FILE`), validação ELF e npm |
| `ElfPatcher.kt` | Reescreve `RUNPATH` de binários importados para `$ORIGIN/../lib` |
| `AgentFragment.kt` | Chat compatível com OpenAI e ferramentas com aprovação |
| `PluginsFragment.kt` | Catálogo de integrações com ações reais de instalação |
| `PluginRows.kt` | Cartões de runtime com marcas, estados e ações |
| `ProjectRows.kt` | Cartões da lista de projetos |
| `ProjectScaffolder.kt` | Criação transacional dos templates de projeto |
| `SqliteConsoleDialog.kt` | Console SQL usando o SQLite nativo do Android |
| `Rows.kt` | `RecyclerView.Adapter` da árvore de arquivos |

Os arquivos ficam em `app/src/main/java/com/mobile/nodeide/`.

### Build

- Kotlin **2.1.0**, AGP **8.9.2**, Gradle **8.11.1** (wrapper), JDK **17**.
- `gradle.properties` já traz `android.useAndroidX=true` e `kotlin.code.style=official`.

```bash
./gradlew assembleDebug   # APK em app/build/outputs/apk/debug/
```

### Abrir no SmartIDE

1. Abra **esta pasta do projeto** no SmartIDE — não crie outro projeto *Empty Activity* por cima.
2. Deixe o SmartIDE sincronizar o Gradle (AGP 8.9.2, Kotlin 2.1.0, AndroidX).
3. Selecione o módulo `app` e execute. Se o SmartIDE exigir um JDK embutido, use **JDK 17**.

Se o SmartIDE gerou um template **Compose**, remova os arquivos do template em `app/src/main/kotlin/com/mobile/nodeide/` (incluindo a `MainActivity.kt` e `ui/theme/`), senão haverá declaração duplicada; este projeto usa XML.

| Copiar | Para (no projeto novo) |
| --- | --- |
| `app/src/main/java/com/mobile/nodeide/*.kt` | `app/src/main/java/com/mobile/nodeide/` |
| `app/src/main/res/layout/*.xml` | `app/src/main/res/layout/` (substituir `activity_main.xml`) |
| `app/src/main/res/values/themes.xml`, `strings.xml` | `app/src/main/res/values/` |
| `app/src/main/AndroidManifest.xml` | `app/src/main/` (manter `INTERNET` e `extractNativeLibs`) |

Dependências esperadas no `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    implementation("com.google.android.material:material:1.12.0")
}
```

`namespace`/`applicationId` = `com.mobile.nodeide`.

## Disponível

- **Editor com números de linha** desenhados na própria `Canvas` (coluna fixa, alinhada à fonte monoespaçada), realce lexical e autocompletar opcional. O realce agora compila cada expressão regular **uma única vez**, com atraso (*debounce*) e dentro de `try/catch`: nenhuma expressão inválida derruba mais o editor, e arquivos com mais de 60 mil caracteres não são realçados (para não travar a digitação).
- **Terminal interativo com seleção e cópia nativas**: um emulador próprio (`TerminalEmulator`) mantém a grade de caracteres e o ANSI, e a saída é renderizada num `TextView` monoespaçado dentro de um `ScrollView` (`TerminalView`). O teclado do sistema digita **direto na tela** (toque = foco), e **toque longo seleciona texto** com a barra flutuante de copiar/selecionar tudo, igual a um terminal real — também há o atalho `⧉ Copiar`. Tem cursor piscando, cores ANSI/256/truecolor, `\r` de barras de progresso, histórico rolável e barra de teclas (`⇤ ← → ↑ ↓ Tab ^C ^D ⏎ clear ⧉ Copiar | > ~ / -`). Cada linha tem exatamente `columns` células e o `COLUMNS`/`LINES` é reexportado antes de cada comando, então o `ls` aparece alinhado. O shell começa **na pasta do projeto aberto**, com `PATH`, `HOME`, `TMPDIR`, `NODE_PATH`, `OPENSSL_CONF` e `SSL_CERT_FILE` do prefixo do app, acompanha o `cd` e roda `ls`, `node` e `npm` no lugar certo.
- **Runtime de Node.js/npm de verdade** em `files/usr` (layout parecido com o do Termux). O npm é baixado do `registry.npmjs.org` e extraído pelo próprio app; quando há Node e npm instalados, o terminal expõe `node`, `npm` e `npx`.
- Criação de projetos HTTP, API Express e Express fullstack em armazenamento privado; importação SAF e clonagem HTTPS com JGit.
- Catálogo de plugins com estado real (**INSTALADO / PRONTO PARA INSTALAR / DEPENDE DO NODE**), diagnóstico do runtime e console SQLite.
- Interface escura com edge-to-edge e insets; **a barra de navegação inferior desaparece quando um projeto está aberto** (o editor ocupa a tela inteira) e o botão Voltar retorna aos projetos.
- Chat com endpoint HTTPS **OpenAI-compatible** `/chat/completions`, histórico em memória e ferramentas de arquivos/comando, todas com aprovação explícita. O ambiente do prefixo também é aplicado aos comandos do agente.

## Node.js e npm

O Android usa **Bionic** como libc; o `node` publicado em nodejs.org é **glibc**. Por isso baixar o `.tar.gz` oficial termina em `No such file or directory` — o kernel procura o interpretador `/lib64/ld-linux-*.so` e ele não existe. O `node` precisa ser compilado contra Bionic (por exemplo, o binário de um Termux instalado no aparelho).

> **Sem simulador de apt/dpkg.** O app não tenta reproduzir o gerenciador de pacotes do Termux em Kotlin. O foco é rodar projetos Node.js (backend/frontend) e `npm install`; o npm já cuida dos pacotes JS locais em `node_modules`. O runtime é importado como executável ou arquivo compactado, e a configuração TLS é extraída dos assets do próprio app.

1. **Empacotado no APK** (offline, nada para o usuário fazer). Duas formas:

   ```bash
   # a) zip/tar.gz com bin/node Bionic + as .so vizinhas, extraído na primeira abertura
   mkdir -p app/src/main/assets/node
   cp node-arm64-v8a.zip app/src/main/assets/node/

   # b) ou um executável de Node para Android (não glibc)
   mkdir -p app/src/main/jniLibs/arm64-v8a
   cp /caminho/para/node app/src/main/jniLibs/arm64-v8a/libnode.so
   ```

   `MainActivity.bootstrapRuntime()` tenta `jniLibs` e depois `assets/node/` (`installBundled()`/`installFromAssets()`).

2. **Importar** (*Plugins → Node.js → Importar executável*): aceita um `node` de Android, `.zip` ou `.tar.gz` com `bin/node` e as bibliotecas. O app valida o ELF de verdade: ABI **e interpretador** (`PT_INTERP` precisa apontar para `/system/bin/linker64`). Um binário glibc é recusado com explicação em vez de falhar depois com "No such file or directory". As `.so` vão para `files/usr/lib` e o `RUNPATH` é ajustado para `$ORIGIN/../lib`.

3. **URL** (*Plugins → Node.js → Baixar de uma URL*) também funciona.

O npm pode ser atualizado em *Plugins → npm* (registry oficial, `latest` pelas dist-tags) e cria `files/usr/bin/npm`/`npx` executando `node npm-cli.js "$@"`. O comando `runtime` no terminal mostra ABI, versão, interpretador ELF, `PATH`, `HOME`, `TMPDIR`, `OPENSSL_CONF` e `SSL_CERT_FILE` efetivos.

### OpenSSL e variáveis de ambiente (correção do "Permission denied")

O `node`/`npm` do Termux é compilado com o prefixo `/data/data/com.termux/files/usr` hardcoded. Como o pacote deste app é `com.mobile.nodeide`, aquele caminho não existe e o OpenSSL falha com "Permission denied", travando `node` e `npm`. A correção é definitiva e vale para **todo** processo filho:

- `NodeRuntime.processBuilder(comando, diretório)` é o **único** ponto de criação de processos (shell, `run()`, agente). Ele chama `prepare()` e injeta o ambiente antes do `start()`.
- `prepare()` cria `files/usr/etc/tls/` e extrai dos assets do app:
  - `openssl.cnf` (de `assets/runtime/openssl.cnf`, com `@PREFIX@`/`@HOME@` substituídos pelos caminhos reais do app);
  - `cert.pem` (bundle de CAs da Mozilla, de `assets/runtime/cert.pem`).
- Se um runtime importado trouxer um `openssl.cnf` com o prefixo do Termux, `prepare()` reescreve essas referências para o prefixo do app.
- O ambiente injeta, entre outras:

  | Variável | Valor |
  | --- | --- |
  | `PREFIX` | `<filesDir>/usr` |
  | `HOME` | `<filesDir>/home` |
  | `TMPDIR` | `<filesDir>/usr/tmp` |
  | `OPENSSL_CONF` | `<filesDir>/usr/etc/tls/openssl.cnf` |
  | `SSL_CERT_FILE` | `<filesDir>/usr/etc/tls/cert.pem` |
  | `NODE_EXTRA_CA_CERTS` / `CURL_CA_BUNDLE` / `GIT_SSL_CAINFO` / `NPM_CONFIG_CAFILE` | mesmo `cert.pem` |
  | `PATH` | `<filesDir>/usr/bin:<PATH do sistema>` |
  | `LD_LIBRARY_PATH` | `<filesDir>/usr/lib:<nativeLibraryDir>` |
  | `PWD` | raiz do projeto ativo |

  `<filesDir>` é o diretório privado do app (`/data/user/0/com.mobile.nodeide/files` no usuário principal).

### Por que targetSdk 28

O Android 10+ bloqueia `execve` em arquivos gravados no diretório privado do app para apps com `targetSdk >= 29` (regra W^X). Como o Node é instalado em `files/usr/bin`, o app usa `targetSdk = 28` — a mesma escolha do Termux. `compileSdk` continua 36. Se você só usar o Node empacotado (`jniLibs`/`assets`), pode voltar para `targetSdk = 36`; nesse caso, porém, o Node baixado do Termux em `files/usr/bin` não poderá ser executado. Por isso `targetSdk = 28` continua fixo, como no Termux.

## Correções desta versão

- **OpenSSL / "Permission denied" resolvido**: todo processo filho passa por `NodeRuntime.processBuilder`, que injeta `OPENSSL_CONF` e `SSL_CERT_FILE` apontando para `files/usr/etc/tls/` (extraído dos assets no `prepare()`). O `node`/`npm` não procura mais o prefixo hardcoded do Termux. Veja a tabela de variáveis acima.
- **Sem simulador de apt/dpkg**: removidos `TermuxPackages.kt` e `Archives.kt` (leitor de `.deb`/índice apt) e a dependência `org.tukaani:xz`. O app foca em rodar projetos Node.js e `npm install`; o runtime é importado como executável/`.zip`/`.tar.gz` e o npm vem do registry oficial.
- **Validação de ELF com interpretador**: `installBinary` lê o `PT_INTERP`. Binário de Linux desktop (glibc) é recusado na hora com mensagem clara; `libnode.so` (biblioteca, sem `main`) continua recusado. O `RUNPATH` de binários/bibliotecas importados é ajustado para `$ORIGIN/../lib`.
- **Diretório de trabalho sempre no projeto**: o `ProcessBuilder` do shell, do `run()` e do agente nasce com `.directory(raizDoProjeto)`. `Executar` e `npm install` chamam `ensureDirectory(projeto)` antes de rodar, então o comando sempre cai na pasta aberta, mesmo depois de um `cd`.
- **Terminal com seleção e cópia nativas**: `TerminalView` agora é um `ScrollView` com um `TextView` monoespaçado e `setTextIsSelectable(true)` — toque longo seleciona texto e a barra flutuante copia, como em qualquer terminal real. Há também o atalho `⧉ Copiar`. A grade é alinhada (cada linha tem `columns` células, sem ligaduras) e o `COLUMNS`/`LINES` é reexportado antes de cada comando, corrigindo o `ls` desalinhado.
- **Ambiente do shell completo**: `PATH`, `HOME`, `TMPDIR`, `NODE_PATH`, `PREFIX`, `LD_LIBRARY_PATH`, `NPM_CONFIG_*`, `SHELL`, `TERM`, `PWD` e as variáveis TLS da tabela acima.
- **targetSdk 28 mantido** por causa da regra W^X (execução de binários em `files/`).

## Limitações importantes (honestas)

- **Sem PTY nativo**: um pseudo-terminal exige `posix_openpt`/`grantpt`/`unlockpt` via NDK (bionic não tem `forkpty`), e este projeto não compila C++ — o sandbox não tem NDK. O terminal é, portanto, um **shell real em pipes com emulação de terminal do lado do app**: há eco, edição de linha, cores, cursor, histórico e rolagem, mas não há `SIGINT` real (o `^C` reabre o shell no mesmo diretório) nem programas de tela cheia (`vim`, `htop`) ou `readline` avançado. Programas que leem `stdin` sem parar podem engolir a próxima linha.
- **O APK do repositório não contém o binário do Node**: ele tem ~40–80 MB por ABI e só existe compilado para Bionic. A instalação é feita importando um executável/`.zip`/`.tar.gz` (*Plugins → Node.js → Importar executável*) ou empacotando `assets/node/` / `jniLibs/<abi>/libnode.so` antes do build. Não há download automático de pacotes `.deb`.
- **`COLUMNS`/`LINES`**: sem PTY não há `SIGWINCH`; o tamanho é reexportado antes de cada comando, então programas que leem o tamanho só na inicialização podem usar o valor antigo até o próximo comando.
- O realce é lexical genérico (não há parser completo de JS/TS/HTML/CSS/JSON/Markdown) e é limitado a 60 mil caracteres.
- MariaDB/MySQL exigem servidor externo e conector, ainda não integrados.
- A clonagem depende do JGit; se falhar em algum aparelho, use a importação por pasta.
- O agente usa apenas o protocolo OpenAI-compatible e não faz streaming de tokens. A chave fica nas preferências privadas sem criptografia; backup do app está desativado.

## Relatório técnico

O detalhamento desta rodada (o que foi corrigido, o que foi testado, o que ainda
não é possível e como validar no aparelho) está em
[`docs/RELATORIO-TERMINAL-E-NODE.md`](docs/RELATORIO-TERMINAL-E-NODE.md).
