package io.github.footermandev.tritium.core.project

import io.github.footermandev.tritium.*
import io.github.footermandev.tritium.accounts.MCVersion
import io.github.footermandev.tritium.accounts.MCVersionType
import io.github.footermandev.tritium.accounts.MicrosoftAuth
import io.github.footermandev.tritium.core.modloader.ModLoader
import io.github.footermandev.tritium.core.modpack.ModSource
import io.github.footermandev.tritium.core.project.templates.ProjectTemplateExecutor
import io.github.footermandev.tritium.core.project.templates.TemplateExecutionResult
import io.github.footermandev.tritium.core.project.templates.generation.GeneratorStepDescriptor
import io.github.footermandev.tritium.core.project.templates.generation.license.AuthorResolver
import io.github.footermandev.tritium.core.project.templates.generation.license.License
import io.github.footermandev.tritium.core.project.templates.generation.license.LicenseGenerator
import io.github.footermandev.tritium.extension.core.CoreSettingValues
import io.github.footermandev.tritium.git.Git
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.koin.getRegistry
import io.github.footermandev.tritium.platform.Platform
import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.qt.setStyle
import io.github.footermandev.tritium.ui.theme.setInvalid
import io.github.footermandev.tritium.ui.widgets.*
import io.github.footermandev.tritium.ui.widgets.constructor_functions.hBoxLayout
import io.github.footermandev.tritium.ui.widgets.constructor_functions.label
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/**
 * Project type for creating Modpack projects.
 */
class ModpackProjectType : ProjectType {
    override val id: String = "modpack"
    override val displayName: String = "Modpack" // TODO: Localization
    override val description: String = "Create a ModPack project"
    override val icon: QIcon = QIcon(TIcons.TrMeta)
    override val order: Int = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = logger()
    private val licenses = getRegistry<License>("core.license")
    private val modLoaders = getRegistry<ModLoader>("core.mod_loader")
    private val modSources = getRegistry<ModSource>("core.mod_source")

    override fun createSetupWidget(
        projectRootHint: Path?,
        initialVars: MutableMap<String, String>
    ): QWidget {
        val panel = QWidget()
        val form = QFormLayout(panel).apply {
            labelAlignment = Qt.AlignmentFlag.AlignLeft.asAlignment()
            formAlignment = Qt.AlignmentFlag.AlignLeft.asAlignment()
            contentsMargins = 0.m
            setSpacing(8)
        }

        // MARK: Set the project Name
        val nameLabel = label("Name:")
        val nameField = QLineEdit().apply {
            text = initialVars.getOrDefault("packName", "")
            textChanged.connect { initialVars["packName"] = this.text }
            maximumWidth = 360
            minimumWidth = 50
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            textChanged.connect {
                if(text.isBlank()) {
                    this.setInvalid(true, "Cannot be empty")
                } else {
                    this.setInvalid(false)
                }
            }
        }
        initialVars["packName"] = nameField.text
        form.addRow(nameLabel, nameField)

        // MARK: Set the project location
        val pathLabel = label("Location:")
        val pathField = InfoLineEditWidget().apply {
            val slash = if(Platform.isWindows) "\\" else "/"
            text = initialVars.getOrDefault("packPath", "~${slash}tritium${slash}${TConstants.Dirs.PROJECTS}")
            tipText = "~ is your home folder"
            textChanged.connect { initialVars["packPath"] = this.text }
            maximumWidth = 360
            minimumWidth = 50
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            textChanged.connect {
                if(text.isBlank()) {
                    this.setInvalid(true, "Cannot be empty")
                } else {
                    this.setInvalid(false)
                }
            }
        }
        initialVars["packPath"] = pathField.text
        form.addRow(pathLabel, pathField)

        // MARK: Set the project Icon
        val iconPreview = QLabel()
        val iconLabel = label("Icon:")

        val iconPathField = QLineEdit().apply {
            text = initialVars.getOrDefault("iconPath", "")
            minimumWidth = 50
            textChanged.connect { initialVars["iconPath"] = this.text }
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        }
        initialVars["iconPath"] = iconPathField.text

        val pickIconBtn = TPushButton {
            icon = QIcon(TIcons.Folder)
            minimumWidth = 50
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Minimum, QSizePolicy.Policy.Fixed)
            toolTip = "Browse..."
        }

        pickIconBtn.onClicked {
            val imageExts = TConstants.Lists.ImageExtensions.distinct()
            val imageFilter = if (imageExts.isNotEmpty()) {
                val patterns = imageExts.joinToString(" ") { "*.$it" }
                "Images ($patterns);;All Files (*)"
            } else {
                "All Files (*)"
            }
            val file = QFileDialog.getOpenFileName(
                panel,
                "Choose Icon",
                "",
                imageFilter
            )
            if (file != null && file.result.isNotBlank()) {
                iconPathField.text = file.result
                initialVars["iconPath"] = file.result
                val pix = QPixmap(file.result)
                if (!pix.isNull) iconPreview.pixmap = pix.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio)
            }
        }

        initialVars["iconPath"]?.let { p ->
            if (p.isNotBlank()) {
                val pix = QPixmap(p)
                if (!pix.isNull) iconPreview.pixmap = pix.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio)
            }
        }

        val iconRow = QWidget()
        val iconRowLayout = hBoxLayout(iconRow) {
            contentsMargins = 0.m
            setSpacing(8)
            addWidget(iconPathField)
            addWidget(pickIconBtn)
            addWidget(iconPreview)
            addStretch(1)
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }
        form.addRow(iconLabel, iconRow)

        // MARK: Set the Minecraft Version
        val mcLabel = label("Minecraft Version:")
        val mcCombo = TComboBox {
            sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
            minimumWidth = 50
        }
        form.addRow(mcLabel, mcCombo)

        // MARK: Set the Mod Loader
        val modLoaderLabel = label("Mod Loader:")
        val modLoaderCombo = TComboBox {
            sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
            minimumWidth = 50
        }
        form.addRow(modLoaderLabel, modLoaderCombo)

        // MARK: Set the Mod Loader Version
        val modLoaderVerLabel = label("Mod Loader Version:")
        val modLoaderVerCombo = TComboBox {
            sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
            minimumWidth = 50
        }
        form.addRow(modLoaderVerLabel, modLoaderVerCombo)

        // MARK: Set the Mod Source
        val sourceLabel = label("Mod Source:")
        val sourceCombo = TComboBox {
            sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
            minimumWidth = 50
        }
        form.addRow(sourceLabel, sourceCombo)

        val separatorLabel = LineLabelWidget("Optional").apply {
            setStyle {
                padding(top = 10, bottom = 10)
            }
            minimumWidth = 50
        }
        form.addRow(separatorLabel)

        // MARK: Set if Git Repository should be initialized
        val gitLabel = label("Create Git Repository:")
        val gitCheckbox = QCheckBox().apply {
            isCheckable = Git.gitExecExists
            isChecked = initialVars.getOrDefault("initGit", "false") == "true"
            toggled.connect { checked ->
                initialVars["initGit"] = if(checked) "true" else "false"
            }
            minimumWidth = 50
        }
        initialVars["initGit"] = if(gitCheckbox.isChecked) "true" else "false"
        form.addRow(gitLabel, gitCheckbox)

        // MARK: Set License
        val licenseLabel = label("License:")
        val licenseCombo = TComboBox {
            sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
            minimumWidth = 50
        }
        form.addRow(licenseLabel, licenseCombo)

        val licenseAuthorLabel = label("License Author:") { visible = false }
        val licenseAuthorField = QLineEdit().apply {
            visible = false
            textChanged.connect { initialVars["licenseAuthor"] = text }
        }
        val licenseAuthorSource = label() { visible = false }

        val licenseAuthorRow = QWidget()
        val licenseAuthorRowLayout = hBoxLayout(licenseAuthorRow) {
            contentsMargins = 0.m
            setSpacing(8)
            addWidget(licenseAuthorField)
            addWidget(licenseAuthorSource)
            addStretch(1)
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }
        form.addRow(licenseAuthorLabel, licenseAuthorRow)
        initialVars["licenseAuthor"] = licenseAuthorField.text

        mcCombo.currentIndexChanged.connect {
            (mcCombo.currentData as? String)?.let { initialVars["minecraftVersion"] = it }
        }
        modLoaderCombo.currentIndexChanged.connect {
            (modLoaderCombo.currentData as? String)?.let { initialVars["modLoader"] = it }
        }
        modLoaderVerCombo.currentIndexChanged.connect {
            (modLoaderVerCombo.currentData as? String)?.let { initialVars["modLoaderVersion"] = it }
        }
        sourceCombo.currentIndexChanged.connect {
            (sourceCombo.currentData as? String)?.let { initialVars["modSource"] = it }
        }


        licenseCombo.currentIndexChanged.connect {
            val lcId = licenseCombo.currentData as? String
            val selected = licenses.all().find { it.id == lcId }

            if(selected != null) {
                if(selected.requiresAuthor) {
                    if (licenseAuthorField.text.isBlank()) {
                        scope.launch {
                            val suggested = try {
                                AuthorResolver.resolvePreferredAuthor()
                            } catch (_: Throwable) {
                                null
                            }

                            if (suggested != null) {
                                runOnGuiThread {
                                    licenseAuthorField.text = suggested.first

                                    licenseAuthorSource.text = "From " + suggested.second
                                    licenseAuthorSource.showThenFade()
                                }
                            }
                        }
                    }
                }
                licenseAuthorLabel.visible = selected.requiresAuthor
                licenseAuthorField.visible = selected.requiresAuthor
            } else {
                licenseAuthorField.visible = false
            }

            (licenseCombo.currentData as? String)?.let { initialVars["license"] = it }
        }

        modLoaders.all().sortedBy { it.order }.forEach { ml -> modLoaderCombo.addItem(ml.displayName, ml.id) }
        modSources.all().sortedBy { it.order }.forEach { ms -> sourceCombo.addItem(ms.displayName, ms.id) }
        licenses.all().sortedBy { it.order }.forEach { lc -> licenseCombo.addItem(lc.name, lc.id) }

        if (modLoaderCombo.count > 0) {
            modLoaderCombo.currentIndex = 0
            (modLoaderCombo.currentData as? String)?.let { initialVars["modLoader"] = it }
        }
        if (sourceCombo.count > 0) {
            sourceCombo.currentIndex = 0
            (sourceCombo.currentData as? String)?.let { initialVars["modSource"] = it }
        }

        fun updateCompatibleVersions() {
            val loaderId = modLoaderCombo.currentData as? String
            val mcVersion = mcCombo.currentData as? String

            if (loaderId == null || mcVersion == null) {
                runOnGuiThread { modLoaderVerCombo.clear() }
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                val loader = modLoaders.all().find { it.id == loaderId }
                val compatible: List<String> = try {
                    loader?.getCompatibleVersions(mcVersion) ?: emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }

                runOnGuiThread {
                    modLoaderVerCombo.clear()
                    compatible.forEach { v -> modLoaderVerCombo.addItem(v, v) }
                    if (modLoaderVerCombo.count > 0) {
                        modLoaderVerCombo.currentIndex = 0
                        (modLoaderVerCombo.currentData as? String)?.let { initialVars["modLoaderVersion"] = it }
                    }
                }
            }
        }

        fun fetchAndPopulateMcVersions() {
            CoroutineScope(Dispatchers.IO).launch {
                val includePreReleases = CoreSettingValues.includePreReleaseMinecraftVersions()
                val releaseTypes = if (includePreReleases) {
                    listOf(MCVersionType.Release, MCVersionType.Snapshot)
                } else {
                    listOf(MCVersionType.Release)
                }
                val versions: List<MCVersion> = try {
                    MicrosoftAuth.getMinecraftVersions(releaseTypes)
                } catch (t: Throwable) {
                    logger.info("Failed fetching Minecraft versions", t)
                    emptyList()
                }

                runOnGuiThread {
                    mcCombo.clear()
                    versions.forEach { ver ->
                        mcCombo.addItem(ver.id, ver.id)
                    }
                    if (mcCombo.count > 0) {
                        mcCombo.currentIndex = 0
                        (mcCombo.currentData as? String)?.let { initialVars["minecraftVersion"] = it }
                    }
                    updateCompatibleVersions()
                }
            }
        }

        mcCombo.currentIndexChanged.connect { updateCompatibleVersions() }
        modLoaderCombo.currentIndexChanged.connect { updateCompatibleVersions() }

        fetchAndPopulateMcVersions()

        return panel
    }

    /**
     * Create the project on disk and write `trproj.json` plus modpack metadata.
     */
    override suspend fun createProject(
        vars: Map<String, String>
    ): TemplateExecutionResult {
        val json = Json { prettyPrint = true }
        val packName = vars["packName"]?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("No Name specified for new project")
        logger.info("Modpack createProject start: name={} vars={}", packName, vars)

        val iconPath = vars["iconPath"]?.trim().orEmpty()

        val packPathRaw = vars["packPath"]?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("No location specified for new project")

        val mcVer    = vars["minecraftVersion"]?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("No Minecraft Version specified for new project")

        val loaderId = vars["modLoader"]?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("No Mod Loader specified for new project")

        val loaderVersion = vars["modLoaderVersion"]?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("No Mod Loader Version specified for new project")

        val sourceId = vars["modSource"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Mods Source not specified for new project")

        val gitInit = vars["initGit"]?.toBoolean() ?: false

        val license = vars["license"]?.trim()
        val licenseAuthor = vars["licenseAuthor"]?.trim()

        // Ensure selections are registered
        val loader = modLoaders.all().find { it.id == loaderId }
            ?: throw IllegalArgumentException("Selected ModLoader '$loaderId' is not registered")

        val source = modSources.all().find { it.id == sourceId }
            ?: throw IllegalArgumentException("Selected ModSource '$sourceId' is not registered")

        val packPath = VPath.get(packPathRaw).expandHome()
        val projectRoot = packPath.resolve(packName)

        withContext(Dispatchers.IO) {
            if(projectRoot.existsNotEmpty()) {
                logger.warn("Aborting project creation, specified directory is not empty.")
                throw IllegalArgumentException("Project directory already exists: $projectRoot")
            } else {
                projectRoot.existsNotEmpty()
            }
        }

        val modpackMeta = ModpackMeta(
            id = packName,
            minecraftVersion = mcVer,
            loader = loader.id,
            loaderVersion = loaderVersion,
            source = source.id,
            license = license,
            icon = if(iconPath.isNotBlank()) "icon.png" else null
        )
        val manifest = json.encodeToString(ModpackMeta.serializer(), modpackMeta)

        val steps = mutableListOf<GeneratorStepDescriptor>()
        steps += GeneratorStepDescriptor(
            "create-modpack-meta",
            "createFile",
            JsonObject(mapOf(
                "path" to JsonPrimitive("trmodpack.json"),
                "template" to JsonPrimitive(manifest),
                "overwrite" to JsonPrimitive(true)
            )),
            affects = listOf("trmodpack.json")
        )

        steps += GeneratorStepDescriptor(
            "create-export-rules",
            "createFile",
            JsonObject(mapOf(
                "path" to JsonPrimitive("trexportrules.json"),
                "template" to JsonPrimitive("{}"),
                "overwrite" to JsonPrimitive(false)
            )),
            affects = listOf("trexportrules.json")
        )

        // Ensure standard instance directories exist
        fun placeholder(path: String) = GeneratorStepDescriptor(
            "placeholder-$path",
            "createFile",
            JsonObject(mapOf(
                "path" to JsonPrimitive("$path/.placeholder"),
                "template" to JsonPrimitive("# placeholder to keep folder in VCS"),
                "overwrite" to JsonPrimitive(false)
            )),
            affects = listOf("$path/**")
        )
        listOf("mods", "config", "defaultconfigs", "logs", "saves").forEach { dir ->
            steps += placeholder(dir)
        }

        if(iconPath.isNotBlank()) {
            val normalizedFileUrl = if(iconPath.startsWith("file://")) iconPath else "file://$iconPath"
            steps += GeneratorStepDescriptor(
                "copy-icon",
                "fetch",
                JsonObject(mapOf(
                    "url" to JsonPrimitive(normalizedFileUrl),
                    "dest" to JsonPrimitive("icon.png")
                )),
                affects = listOf("icon.png")
            )
        }

        if(gitInit) {
            steps += GeneratorStepDescriptor(
                "gitignore",
                "createFile",
                JsonObject(mapOf(
                    "path" to JsonPrimitive(".gitignore"),
                    "template" to JsonPrimitive(".tr/\ntr*.json\n"),
                    "overwrite" to JsonPrimitive(false)
                )),
                affects = listOf(".gitignore")
            )
        }

        // Ensure project root exists before executing steps
        projectRoot.mkdirs()

        val execResult = ProjectTemplateExecutor.run(
            templateId = "builtin.modpack:$packName",
            projectRoot = projectRoot.toJPath(),
            variables = vars,
            steps = steps
        )
        logger.info("Modpack template steps finished: success={} root={}", execResult.successful, projectRoot)

        // Only write project definition if generation succeeded
        if(execResult.successful) {
            val iconValue = if(iconPath.isNotBlank()) "icon.png" else TIcons.defaultProjectIcon
            val rawMeta = buildJsonObject {
                put("metaPath", "trmodpack.json")
            }
            val trMeta = ProjectFiles.buildMeta(
                type = id,
                name = packName,
                icon = iconValue,
                schemaVersion = ModpackTemplateDescriptor.currentSchema,
                meta = rawMeta
            )
            ProjectFiles.writeTrProject(projectRoot, trMeta)
            logger.info("Wrote trproj.json for {}", packName)

            // Perform synchronously
            if(!license.isNullOrBlank() && !license.equals("none", ignoreCase = true)) {
                val selected = licenses.all().find { it.id == license }
                if(selected != null) {
                    val author = if(selected.requiresAuthor) {
                        licenseAuthor?.takeIf { it.isNotBlank() }
                            ?: AuthorResolver.resolvePreferredAuthor()?.first
                    } else null
                    val out = projectRoot.toJPath().resolve("LICENSE")
                    logger.info("Generating license {} (author={})", selected.id, author)
                    LicenseGenerator.generateFile(selected, out, authorOpt = author)
                }
            }

            // Kick heavy downloads to background
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                logger.info("Background bootstrap start for {} (MC {}, loader {} {})", packName, mcVer, loader.id, loaderVersion)
                try {
                    coroutineScope {
                        val mcJob = async {
                            val mcStart = System.currentTimeMillis()
                            val ok = MicrosoftAuth.setupMinecraftInstance(mcVer, projectRoot)
                            logger.info("Minecraft setup {} ({})", if(ok) "ok" else "failed", formatDurationMs(System.currentTimeMillis() - mcStart))
                            ok
                        }

                        val gitJob = async {
                            if(gitInit) {
                                try {
                                    logger.info("Initializing git repo in {}", projectRoot)
                                    Git.initRepo(projectRoot)
                                } catch (t: Throwable) {
                                    logger.warn("Git init failed in {}", projectRoot, t)
                                }
                            }
                        }

                        val mcOk = mcJob.await()
                        if (mcOk) {
                            val loaderStart = System.currentTimeMillis()
                            logger.info("Installing loader {} {} into {}", loader.id, loaderVersion, projectRoot)
                            val ok = loader.installClient(loaderVersion, mcVer, projectRoot)
                            logger.info("Loader install {} ({})", if(ok) "ok" else "failed", formatDurationMs(System.currentTimeMillis() - loaderStart))
                            if (ok) {
                                val merged = MicrosoftAuth.writeMergedVersionJson(mcVer, loader.id, loaderVersion, projectRoot)
                                logger.info("Merged version json written to {}", merged?.toAbsolute() ?: "null")
                            }
                        } else {
                            logger.warn("Skipping loader install; Minecraft setup failed for {}", packName)
                        }

                        gitJob.await()
                    }
                } catch (t: Throwable) {
                    logger.warn("Background bootstrap failed for {}", packName, t)
                }
                logger.info("BACKGROUND BOOTSTRAP FINISHED for {}", packName)
            }
        }

        logger.info("Modpack createProject finished: success={}", execResult.successful)
        return execResult
    }


}
