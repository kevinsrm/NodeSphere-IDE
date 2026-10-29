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
| `Ansi.kt` | Interpreta cores/negrito ANSI da saída do terminal |
| `ShellSession.kt` | Shell persistente (`/system/bin/sh`) com prompt, histórico e `cwd` |
| `NodeRuntime.kt` | Prefixo `usr/`, instalação de Node.js e download do npm |
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
- **Terminal integrado no estilo Termux**: prompt `nodesphere:~/projeto$`, histórico (`↑`/`↓`), cores ANSI (`ls --color`, npm, node), `clear`, `^C`, `^D`, `help` e `runtime`, acompanhamento do diretório atual (inclusive após `cd`) e ambiente próprio do prefixo (`PREFIX`, `HOME`, `TMPDIR`, `PATH`, `LD_LIBRARY_PATH`). O painel é redimensionável (arraste a barra) e abre/fecha por toque.
- **Runtime de Node.js/npm de verdade** em `files/usr` (layout parecido com o do Termux). O npm é baixado do `registry.npmjs.org` e extraído pelo próprio app; quando há Node e npm instalados, o terminal expõe `node`, `npm` e `npx`.
- Criação de projetos HTTP, API Express e Express fullstack em armazenamento privado; importação SAF e clonagem HTTPS com JGit.
- Catálogo de plugins com estado real (**INSTALADO / PRONTO PARA INSTALAR / DEPENDE DO NODE**), diagnóstico do runtime e console SQLite.
- Interface escura com edge-to-edge e insets; **a barra de navegação inferior desaparece quando um projeto está aberto** (o editor ocupa a tela inteira) e o botão Voltar retorna aos projetos.
- Chat com endpoint HTTPS **OpenAI-compatible** `/chat/completions`, histórico em memória e ferramentas de arquivos/comando, todas com aprovação explícita. O ambiente do prefixo também é aplicado aos comandos do agente.

## Node.js e npm

O aplicativo **executa** Node.js e npm, mas o binário do Node precisa estar presente no aparelho: não existe build oficial de Node.js para Android, e os binários de desktop (glibc) não rodam em Android (bionic). Há três caminhos, todos no app:

1. **Empacotado no APK** (melhor experiência, nada para o usuário fazer):

   ```bash
   mkdir -p app/src/main/jniLibs/arm64-v8a
   cp /caminho/para/node app/src/main/jniLibs/arm64-v8a/libnode.so
   ./gradlew assembleDebug
   ```

   O `libnode.so` é extraído para `nativeLibraryDir` (por isso `android:extractNativeLibs="true"` e `useLegacyPackaging = true`), que é o único diretório do app onde o Android permite executar arquivos. Na primeira abertura, `MainActivity.bootstrapRuntime()` instala esse Node em `files/usr/bin/node`, cria os atalhos `npm`/`npx` e baixa o npm. Repita por ABI (`armeabi-v7a`, `x86_64`) se quiser cobrir todos os aparelhos.

2. **Importação** em *Plugins → Node.js → Importar executável*: aceita um `node` (ELF), um `.zip` ou um `.tar.gz`. O app valida o cabeçalho ELF (máquina/ABI e presença de `PT_INTERP` — recusa `libnode.so`, que é biblioteca e não executável) e copia as `.so` vizinhas para `files/usr/lib`.

3. **URL** em *Plugins → Node.js → Baixar de uma URL*, para um `.zip`/`.tar.gz`/executável.

Com Node instalado, *Plugins → npm → Instalar* baixa `npm` do registry oficial (10.9.2 por padrão, ou a versão `latest` informada pelas dist-tags) e cria `files/usr/bin/npm`, que executa `node npm-cli.js "$@"`.

Se você já usa Termux no aparelho, o binário dele serve: `cp $PREFIX/bin/node /sdcard/Download/node` e importe esse arquivo (as bibliotecas de `$PREFIX/lib` também podem ser importadas em um `.zip` junto do `node`). O comando `runtime` no terminal mostra versões, caminhos e ABI para diagnóstico.

### Por que targetSdk 28

O Android 10+ bloqueia `execve` em arquivos gravados no diretório privado do app para apps com `targetSdk >= 29` (regra W^X). Como o Node é instalado em `files/usr/bin`, o app usa `targetSdk = 28` — a mesma escolha do Termux. `compileSdk` continua 36. Se você só for usar o Node empacotado em `jniLibs` (caminho 1), pode voltar para `targetSdk = 36`; nesse caso, porém, os caminhos 2 e 3 não poderão executar o binário importado.

## Correções desta versão

- **Crash ao abrir arquivo / ao digitar**: a regra de realce de HTML era `</?[a-zA-Z][a-zA-Z0-9:-]*|/?>|\{\{[^}]*}}`. No motor de regex do Android (ICU), o `}}` não escapado é erro de sintaxe — a exceção `PatternSyntaxException` ("Syntax error in regexp pattern near index 40") era lançada dentro do `TextWatcher`, fechando o app. As chaves agora são escapadas (`\{\{[^}]*\}\}`) e todas as regras foram validadas no ICU; além disso, o realce nunca propaga exceção.
- **Números de linha** e alternância em *Editor → Números de linha*.
- **Barra inferior** escondida no editor e botão Voltar tratado pelo fragmento.
- **Terminal** reescrito (prompt, histórico, ANSI, ambiente, cores, atalhos).
- **Runtime**: gerenciador de Node/npm, instalador real do npm, ações no catálogo de plugins e ambiente aplicado ao shell do editor e às ferramentas do agente.

## Limitações importantes (honestas)

- **O APK do repositório ainda não contém o binário do Node** (~60 MB por ABI): ele não pode ser baixado nem compilado neste ambiente (sem NDK, Maven ou assets de release acessíveis). O que está pronto é toda a integração; para "vir instalado", siga o passo 1 de [Node.js e npm](#nodejs-e-npm) — é um `cp` antes do build.
- **Sem PTY**: o Android não expõe pseudo-terminais para apps sem código nativo. O terminal é um shell real em pipes, com prompt/histórico/cores, mas sem eco de teclas, tela cheia (`vim`, `htop`), `Ctrl+C` real ou `readline`. `^C` encerra o shell e reabre no mesmo diretório; um programa que lê `stdin` continuamente pode engolir o próximo comando.
- O realce é lexical genérico (não há parser completo de JS/TS/HTML/CSS/JSON/Markdown) e é limitado a 60 mil caracteres.
- MariaDB/MySQL exigem servidor externo e conector, ainda não integrados.
- A clonagem depende do JGit; se falhar em algum aparelho, use a importação por pasta.
- O agente usa apenas o protocolo OpenAI-compatible e não faz streaming de tokens. A chave fica nas preferências privadas sem criptografia; backup do app está desativado.
