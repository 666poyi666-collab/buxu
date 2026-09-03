package com.poyi.watchintervals.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source contracts for responsive phone interaction surfaces. */
public class PhoneInteractionResourceTest {
    @Test public void connectionStatusLivesInsideTheNamedTopBarTarget() throws Exception {
        String app = source("PhoneApp.kt");
        assertTrue(app.contains("连接状态：${state.connection.label}，打开设备设置"));
        assertTrue(app.contains(".clickable(role = Role.Button) { onSetup() }"));
        assertTrue(app.contains("PhoneStatusDot(color = toneColor(state.connection.tone)"));
        assertTrue(app.contains("PhoneUiContract.connectionStatusLabel("));
        assertTrue(app.contains("PhoneUiContract.showBottomNavigation("));
        assertTrue(app.contains("compactLayout = compactLayout"));
        assertFalse(app.contains("imageVector = PhoneIcons.Forward"));
    }

    @Test public void setupSheetUsesAvailableHeightAndAvoidsSystemUi() throws Exception {
        String setup = source("SetupSheet.kt");
        assertTrue(setup.contains(".fillMaxHeight(0.88f)"));
        assertTrue(setup.contains(".imePadding()"));
        assertTrue(setup.contains(".navigationBarsPadding()"));
        assertFalse(setup.contains(".height(560.dp)"));
        assertFalse(setup.contains(".clickable(enabled = false)"));
    }

    @Test public void groupActionsUseOneOverflowMenuAndWorkoutAvoidsDecorativeRing() throws Exception {
        String plan = source("PlanScreen.kt");
        String workout = source("WorkoutScreen.kt");
        assertTrue(plan.contains("PhoneIcons.More"));
        assertTrue(plan.contains("DropdownMenuItem("));
        assertTrue(plan.contains("SegmentedChoice("));
        assertTrue(plan.contains("viewModel.selectStageKind(index, it)"));
        assertTrue(plan.contains("viewModel.selectStageUnit(index, it)"));
        assertTrue(plan.contains("删除分组及 ${group.plans.size} 个安排"));
        assertTrue(plan.contains("全部删除"));
        assertTrue(plan.contains("viewModel.deleteGroup(group.id)"));
        assertTrue(plan.contains("PlanGroupPicker("));
        assertTrue(plan.contains("viewModel.selectDraftGroup(id, name)"));
        assertFalse(plan.contains("onValueChange = { viewModel.updateDraft(group = it) }"));
        assertFalse(source("PhoneViewModel.kt").contains("if (members.isEmpty()) continue"));
        assertFalse(workout.contains("ActivityRing"));
        assertFalse(workout.contains(".heightIn(min = 250.dp)"));
        assertFalse(workout.contains(".height(250.dp)"));
    }

    @Test public void phoneUsesCompactToolingInsteadOfFloatingGlassAndGiantType() throws Exception {
        String app = source("PhoneApp.kt");
        String theme = source("theme/PhoneTheme.kt");
        String components = source("components/PhoneComponents.kt");
        assertFalse(app.contains("PhoneGlassSurface"));
        assertTrue(app.contains(".background(PhoneColor.Navigation)"));
        assertTrue(theme.contains("fontSize = 24.sp"));
        assertTrue(source("theme/PhoneDimens.kt").contains("navigation = 60.dp"));
        assertTrue(source("theme/PhoneDimens.kt").contains("brandMark = 24.dp"));
        assertTrue(components.contains("PhoneAction.Primary -> ActionPalette(PhoneColor.Move"));
    }

    @Test public void technicalConnectionFieldsStayCollapsedUntilRequested() throws Exception {
        String setup = source("SetupSheet.kt");
        assertTrue(setup.contains("advancedConnectionVisible"));
        assertTrue(setup.contains("cloudConfigurationVisible"));
        assertTrue(setup.contains("if (advancedConnectionVisible)"));
        assertTrue(setup.contains("if (cloudConfigurationVisible)"));
        assertFalse(setup.contains("生产链路为"));
    }

    @Test public void idleConnectedWorkoutKeepsTheStartActionVisible() throws Exception {
        String viewModel = source("PhoneViewModel.kt");
        assertTrue(viewModel.contains("actionsFor(live, ready)"));
        assertTrue(viewModel.contains(
                "live == null -> if (transportReady) listOf(WorkoutAction.Start)"));
    }

    @Test public void todayIsTheDefaultWorkflowAndPlanLibraryIsEnteredOnDemand() throws Exception {
        String plan = source("PlanScreen.kt");
        assertTrue(plan.contains("PhonePageHeader(title = \"今天\""));
        assertTrue(plan.contains("if (live == null) \"打开训练控制\" else \"查看实时训练\""));
        assertTrue(plan.contains("text = \"管理计划\""));
        assertTrue(plan.contains("if (libraryVisible)"));
    }

    @Test public void cloudDisconnectIsVisibleBeforeAChatGptEditCanBeMissed() throws Exception {
        String app = source("PhoneApp.kt");
        String contract = source("PhoneUiContract.kt");
        String plan = source("PlanScreen.kt");
        String setup = source("SetupSheet.kt");
        String viewModel = source("PhoneViewModel.kt");
        assertTrue(app.contains("PhoneUiContract.connectionStatusLabel("));
        assertTrue(contract.contains("云端未连接"));
        assertTrue(plan.contains("ChatGPT 计划不会下发"));
        assertTrue(setup.contains("ChatGPT 的计划不会到达手机或手表"));
        assertTrue(viewModel.indexOf("CloudSnapshotSync.sync(app)")
                < viewModel.indexOf("PhoneSyncOutbox.ensureCurrentLibrary(app)"));
        assertTrue(viewModel.contains("云端、手机、手表已一致"));
    }

    @Test public void primaryPhoneCommandsUseTheSharedVectorIconSlot() throws Exception {
        String components = source("components/PhoneComponents.kt");
        String workout = source("WorkoutScreen.kt");
        String plan = source("PlanScreen.kt");
        assertTrue(components.contains("icon: ImageVector? = null"));
        assertTrue(components.contains("Icon(imageVector = icon"));
        assertTrue(workout.contains("icon = actionIcon(action)"));
        assertTrue(plan.contains("icon = PhoneIcons.Play"));
        assertTrue(plan.contains("icon = PhoneIcons.Check"));
    }

    private static String source(String file) throws Exception {
        Path working = Paths.get(System.getProperty("user.dir"));
        Path root = Files.isDirectory(working.resolve("phone")) ? working : working.getParent();
        Path path = root.resolve("phone/src/main/kotlin/com/poyi/watchintervals/phone/ui").resolve(file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
