<div align="center">

# 🌿 Launcher

**Um launcher Android calmo.**

Sem feed, sem assistente, sem barulho — só a sua tela inicial, do jeito que você desenhar.

![Android](https://img.shields.io/badge/Android-8.0+-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![Versão](https://img.shields.io/badge/vers%C3%A3o-1.2.3-222)

</div>

---

## ✍️ Desenhe para abrir

O que mais faz falta depois de usar: **gestos desenhados com dois dedos**.
Um círculo abre a câmera, um "M" abre a música, um raio liga a lanterna. Na home, na gaveta — e, com o serviço de acessibilidade, até por cima da tela de bloqueio e de outros apps.

Cada gesto abre o que você quiser: um app, um atalho dentro de um app, um link, voltar, recentes, a cortina de notificações.

## O que tem

- 🏠 **Home em dois estilos** — *Dia em linha* ou *Clássico*, com relógios e clima ao vivo.
- 🧩 **Widgets que você arrasta** — relógio, timer, tarefas, música tocando, agenda, notificações, mapa, "agora", céu.
- 🔎 **Gaveta que entende você** — busca tolerante a erro de digitação e acento, índice alfabético na borda, apps ocultos, apelidos e atalhos.
- 🌄 **Papéis de parede** — foto ou animados, tema claro e escuro.
- 💾 **Backup** — gestos, tarefas, papel de parede, preferências e listas num arquivo só.
- 🌍 **Português, español, English.**

## Compilar

Precisa de **JDK 21** e do **Android SDK**.

```bash
export JAVA_HOME=~/jdk-21 ANDROID_HOME=~/android-sdk
./gradlew :app:testDebugUnitTest      # testes
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

<details>
<summary><b>Build de release assinado</b></summary>

<br>

A chave de assinatura **não fica no repositório**. Para gerar um APK de release, crie na raiz do projeto um `keystore.properties` (ignorado pelo git):

```properties
storeFile=minha-chave.jks
storePassword=…
keyAlias=…
keyPassword=…
```

```bash
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Sem esse arquivo, o build de release sai sem assinatura — o de debug funciona normalmente.

</details>

## Por dentro

```
app/src/main/java/com/aura/launcher/
  apps/        índice e busca da gaveta
  gestures/    reconhecimento dos gestos desenhados
  sketch/      modelo dos traços
  ui/home      home, widgets e camada de gestos
  ui/drawer    gaveta de apps
  ui/settings  configurações
  ui/theme     temas e papéis de parede animados
```

- `applicationId`: `com.villamediana.launcher` — o código Kotlin continua no pacote `com.aura.launcher`.
- Nasceu do LauncherAI: é o mesmo launcher, com tudo que era de IA removido.

---

<div align="center">
<sub>Feito por <a href="https://github.com/Villamediana">Miguel Villamediana</a> · menos tela, mais vida 🌿</sub>
</div>
