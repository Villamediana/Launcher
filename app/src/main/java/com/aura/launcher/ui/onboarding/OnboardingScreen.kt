package com.aura.launcher.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.AuraApp
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.components.ActionButton
import com.aura.launcher.ui.theme.Aura
import androidx.compose.ui.res.stringResource
import com.aura.launcher.R
import com.aura.launcher.data.AppLanguage
import com.aura.launcher.ui.defaultLanguageFor
import kotlinx.coroutines.launch

private enum class Step { LANGUAGE, WELCOME, HOME, PERMISSIONS }

/**
 * The steps this install actually walks through.
 *
 * The order is the order the things depend on each other in. Language first,
 * because everything after it is written in the answer. Then what the launcher
 * is, then the home role — which relaunches the process, so anything asked
 * before it would be asked again. Permissions last, because the home screen's
 * cards are blank without them.
 */
private val steps: List<Step> = Step.entries.toList()

@Composable
fun OnboardingScreen(
    app: AuraApp,
    settings: Settings,
    onFinished: () -> Unit,
) {
    val colors = Aura.colors
    val context = LocalContext.current
    val homeRole = rememberHomeRoleState()

    val scope = rememberCoroutineScope()

    // The step lives in settings, not in memory: granting the home role makes
    // Android relaunch Aura as the home app, which discards everything held by
    // the old Activity. Reading it back is what lets setup carry on where it
    // left off instead of starting over.
    val index = settings.onboardingStep.coerceIn(0, steps.lastIndex)
    val step = steps[index]

    fun goTo(next: Int) {
        val target = next.coerceIn(0, steps.lastIndex)
        scope.launch { app.settingsRepository.update { it.copy(onboardingStep = target) } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        Spacer(Modifier.height(20.dp))

        StepDots(
            count = steps.size,
            current = index,
            onSelect = { goTo(it) },
        )

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(320)) { w -> dir * w / 5 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(260)) { w -> -dir * w / 6 } + fadeOut(tween(160)))
            },
            label = "onboardingStep",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (current) {
                    Step.LANGUAGE -> LanguageStep(
                        selected = settings.language ?: defaultLanguageFor(context),
                        onSelect = { choice ->
                            scope.launch {
                                app.settingsRepository.update { it.copy(language = choice) }
                            }
                        },
                    )
                    Step.WELCOME -> WelcomeStep()
                    Step.HOME -> HomeStep(isDefault = homeRole.isDefault, onRequest = homeRole.request)
                    Step.PERMISSIONS -> PermissionsStep(app)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // The primary action is always the same shape and place, so moving
        // through setup is one repeated gesture rather than a hunt.
        val isLast = index == steps.lastIndex
        ActionButton(
            text = when {
                isLast -> stringResource(R.string.ob_start)
                else -> stringResource(R.string.ob_continue)
            },
        ) {
            if (isLast) onFinished() else goTo(index + 1)
        }

        Spacer(Modifier.height(10.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = if (index == 0) stringResource(R.string.ob_step_count)
                else stringResource(R.string.ob_back),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (index == 0) Modifier
                        else Modifier.clickable { goTo(index - 1) }
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
    }
}

// -------------------------------------------------------------------- steps --

/**
 * The first thing asked, because everything after it is written in the answer.
 *
 * It opens on the phone's own language rather than on English, so for most
 * people the step is a confirmation rather than a decision — and each option is
 * written in its own language, which is the only labelling that needs no
 * translation to be understood by the person looking for it.
 */
@Composable
private fun LanguageStep(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    val colors = Aura.colors
    Column {
        Spacer(Modifier.height(40.dp))
        StepHeader(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.ob_language_title),
            subtitle = stringResource(R.string.ob_language_subtitle),
        )
        Spacer(Modifier.height(24.dp))

        for (choice in AppLanguage.entries) {
            val chosen = choice == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (chosen) colors.accentSoft else colors.surface)
                    .clickable { onSelect(choice) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    text = choice.nativeName(),
                    fontSize = 17.sp,
                    color = if (chosen) colors.accent else colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (chosen) {
                    Text("✓", fontSize = 17.sp, color = colors.accent)
                }
            }
        }
    }
}

/** Each language named in itself — the one label nobody has to translate. */
private fun AppLanguage.nativeName(): String = when (this) {
    AppLanguage.ENGLISH -> "English"
    AppLanguage.PORTUGUESE -> "Português"
    AppLanguage.SPANISH -> "Español"
}

@Composable
private fun WelcomeStep() {
    val colors = Aura.colors
    Column {
        Spacer(Modifier.height(40.dp))
        BreathingMark()
        Spacer(Modifier.height(36.dp))
        Text(
            text = "Launcher",
            fontSize = 46.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-1.8).sp,
            color = colors.ink,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ob_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(28.dp))
        Bullet(stringResource(R.string.ob_welcome_bullet_1))
        Bullet(stringResource(R.string.ob_welcome_bullet_2))
        Bullet(stringResource(R.string.ob_welcome_bullet_3))
    }
}

@Composable
private fun HomeStep(isDefault: Boolean, onRequest: () -> Unit) {
    val colors = Aura.colors
    Column {
        StepHeader(
            icon = Icons.Rounded.Home,
            title = stringResource(R.string.ob_home_title),
            subtitle = stringResource(R.string.ob_home_subtitle),
        )
        Spacer(Modifier.height(24.dp))

        if (isDefault) {
            DoneCard(stringResource(R.string.ob_home_done))
        } else {
            ActionButton(stringResource(R.string.ob_home_set_default), filled = false, onClick = onRequest)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.ob_home_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
            )
        }
    }
}

/** One numbered prerequisite, with its own state and its own shortcut. */
@Composable
private fun NumberedAction(
    number: Int,
    title: String,
    done: Boolean,
    actionLabel: String?,
    onClick: () -> Unit = {},
) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (done) colors.sage.copy(alpha = 0.08f) else colors.surface)
            .border(
                1.dp,
                if (done) colors.sage.copy(alpha = 0.22f) else colors.hairline,
                RoundedCornerShape(16.dp),
            )
            .then(if (actionLabel != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (done) colors.sage else colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Rounded.Check, null, tint = colors.surface, modifier = Modifier.size(14.dp))
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) colors.sage else colors.ink,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
            )
        }
    }
}

// --------------------------------------------------------------- components --

/** The mark on the welcome step, breathing slowly. */
@Composable
private fun BreathingMark() {
    val colors = Aura.colors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "mark")
    val scale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(2600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "markScale",
    )

    Box(
        modifier = Modifier.size(96.dp).scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(48.dp))
                .background(colors.accent.copy(alpha = 0.07f))
        )
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .border(1.5.dp, colors.ink.copy(alpha = 0.55f), RoundedCornerShape(32.dp))
        )
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colors.accent)
        )
    }
}

@Composable
internal fun StepHeader(icon: ImageVector, title: String, subtitle: String) {
    val colors = Aura.colors
    Column {
        Spacer(Modifier.height(32.dp))
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(colors.accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-0.8).sp,
            color = colors.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.inkFaint)
    }
}

@Composable
private fun Bullet(text: String) {
    val colors = Aura.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.accent.copy(alpha = 0.7f))
        )
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
    }
}

@Composable
private fun DoneCard(text: String) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.sage.copy(alpha = 0.10f))
            .border(1.dp, colors.sage.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Icon(Icons.Rounded.Check, null, tint = colors.sage, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.sage)
    }
}

/** Progress dots; the active one stretches into a bar. */
@Composable
private fun StepDots(count: Int, current: Int, onSelect: (Int) -> Unit) {
    val colors = Aura.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            val width by animateDpAsState(
                targetValue = if (active) 26.dp else 7.dp,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
                label = "dotWidth",
            )
            val alpha by animateFloatAsState(
                targetValue = if (i <= current) 1f else 0.30f,
                label = "dotAlpha",
            )
            Box(
                Modifier
                    .padding(end = 7.dp)
                    .width(width)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (active) colors.accent.copy(alpha = alpha)
                        else colors.ink.copy(alpha = 0.18f * alpha)
                    )
                    .clickable { onSelect(i) }
            )
        }
    }
}
