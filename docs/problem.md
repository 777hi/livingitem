[07:12:57] [Render thread/INFO] [minecraft/TextureAtlas]: Created: 1024x1024x0 minecraft:textures/atlas/gui.png-atlas
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader rendertype_entity_translucent_emissive could not find sampler named Sampler2 in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/EffectInstance]: Shader anvilcraft:blur could not find uniform named InSize in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/EffectInstance]: Shader anvilcraft:blur could not find uniform named InSize in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/EffectInstance]: Shader anvilcraft:blur could not find uniform named InSize in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/EffectInstance]: Shader anvilcraft:blur could not find uniform named InSize in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/EffectInstance]: Shader anvilcraft:apply_bloom could not find uniform named InSize in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader ars_nouveau:rainbow_entity could not find uniform named IViewRotMat in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader ars_nouveau:blamed_entity could not find uniform named IViewRotMat in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader anvilcraft:rendertype_laser could not find sampler named Sampler2 in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader anvilcraft:selection could not find uniform named AntiAliasingRadius in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader anvilcraft:rendertype_lightning could not find sampler named Sampler0 in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader anvilcraft:rendertype_lightning could not find sampler named Sampler2 in the specified shader program.
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader anvilcraft:scan_preview could not find uniform named OutSize in the specified shader program.
[07:12:57] [Render thread/INFO] [de.an.li.v2.fo.ALFPipelines/]: Registered SDF_TEXT shader: anvillib_font:sdf_text
[07:12:57] [Render thread/WARN] [minecraft/ShaderInstance]: Shader anvillib:selection could not find uniform named AntiAliasingRadius in the specified shader program.
[07:12:57] [Render thread/INFO] [ne.ne.ne.cl.en.an.js.AnimationLoader/]: Loaded 0 entity animations
[07:12:57] [Render thread/INFO] [de.an.re.ag.cl.fe.ma.GuideDocumentCache/]: Preloaded 439 guide markdown files
[07:12:57] [Render thread/INFO] [de.an.re.ag.cl.fe.st.AgeratumStructureTemplateManager/]: Loaded 18 structure template(s) from assets/ageratum/
[07:12:57] [Render thread/INFO] [Veil/]: Loaded 3 framebuffers
[07:12:57] [Render thread/INFO] [Veil/]: Loaded 6 post pipelines
[07:12:57] [Render thread/INFO] [Veil/]: Loaded 4 render types
[07:12:57] [Render thread/INFO] [Veil/]: Enabled bloom pipeline
[07:12:57] [Render thread/INFO] [Veil/]: Loaded 0 quasar particles
[07:12:57] [Render thread/INFO] [Veil/]: Loaded 0 templates
[07:12:57] [Render thread/INFO] [Veil/]: Loaded 0 modules
[07:12:58] [Render thread/INFO] [minecraft/TextureAtlas]: Created: 512x512x0 twilightforest:textures/atlas/magic_paintings.png-atlas
[07:12:58] [Render thread/INFO] [minecraft/TextureAtlas]: Created: 256x256x0 jei:textures/atlas/gui.png-atlas
[07:12:58] [Render thread/INFO] [xa.li.XaeroLib/]: Successfully reloaded the XaeroLib shaders!
[07:12:58] [Render thread/INFO] [minecraft/TextureAtlas]: Created: 512x256x0 mekanism:textures/atlas/robit.png-atlas
[07:12:58] [Render thread/INFO] [gu.in.GuideReloadListener/]: Data driven guides: []
[07:12:58] [Render thread/INFO] [flywheel/backend/shaders/]: Loaded 77 shader sources in 12.808 ms
[07:12:58] [Render thread/INFO] [co.si.cr.Create/]: Loaded 58 train hat configurations.
[07:12:58] [Render thread/INFO] [Iris/]: Creating pipeline for dimension minecraft:overworld
[07:13:06] [NeoForge Version Check/WARN] [ne.ne.fm.VersionChecker/]: Failed to process update information
java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:917) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133) ~[java.net.http:?] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.openUrlString(VersionChecker.java:129) ~[loader-4.0.44.jar%23161!/:4.0] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.process(VersionChecker.java:162) [loader-4.0.44.jar%23161!/:4.0] {}
	at java.base/java.lang.Iterable.forEach(Iterable.java:75) [?:?] {re:mixin}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.run(VersionChecker.java:105) [loader-4.0.44.jar%23161!/:4.0] {}
Caused by: java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:68) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
Caused by: java.net.ConnectException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:69) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
[07:13:06] [NeoForge Version Check/INFO] [ne.ne.fm.VersionChecker/]: [twilightforest] Starting version check at https://gh.tamaized.com/TeamTwilight/twilightforest/update.json?m=1.21.1&l=NeoForge&v=21.1.249
[07:13:22] [NeoForge Version Check/WARN] [ne.ne.fm.VersionChecker/]: Failed to process update information
java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:917) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133) ~[java.net.http:?] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.openUrlString(VersionChecker.java:129) ~[loader-4.0.44.jar%23161!/:4.0] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.process(VersionChecker.java:162) [loader-4.0.44.jar%23161!/:4.0] {}
	at java.base/java.lang.Iterable.forEach(Iterable.java:75) [?:?] {re:mixin}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.run(VersionChecker.java:105) [loader-4.0.44.jar%23161!/:4.0] {}
Caused by: java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:68) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
Caused by: java.net.ConnectException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:69) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
[07:13:22] [NeoForge Version Check/INFO] [ne.ne.fm.VersionChecker/]: [commoncapabilities] Starting version check at https://raw.githubusercontent.com/CyclopsMC/Versions/master/neoforge_update/common-capabilities.json
[07:13:37] [NeoForge Version Check/WARN] [ne.ne.fm.VersionChecker/]: Failed to process update information
java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:917) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133) ~[java.net.http:?] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.openUrlString(VersionChecker.java:129) ~[loader-4.0.44.jar%23161!/:4.0] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.process(VersionChecker.java:162) [loader-4.0.44.jar%23161!/:4.0] {}
	at java.base/java.lang.Iterable.forEach(Iterable.java:75) [?:?] {re:mixin}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.run(VersionChecker.java:105) [loader-4.0.44.jar%23161!/:4.0] {}
Caused by: java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:68) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
Caused by: java.net.ConnectException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:69) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
[07:13:37] [NeoForge Version Check/INFO] [ne.ne.fm.VersionChecker/]: [integratedtunnels] Starting version check at https://raw.githubusercontent.com/CyclopsMC/Versions/master/neoforge_update/integrated-tunnels.json
[07:13:52] [NeoForge Version Check/WARN] [ne.ne.fm.VersionChecker/]: Failed to process update information
java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:917) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133) ~[java.net.http:?] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.openUrlString(VersionChecker.java:129) ~[loader-4.0.44.jar%23161!/:4.0] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.process(VersionChecker.java:162) [loader-4.0.44.jar%23161!/:4.0] {}
	at java.base/java.lang.Iterable.forEach(Iterable.java:75) [?:?] {re:mixin}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.run(VersionChecker.java:105) [loader-4.0.44.jar%23161!/:4.0] {}
Caused by: java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:68) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
Caused by: java.net.ConnectException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:69) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
[07:13:52] [NeoForge Version Check/INFO] [ne.ne.fm.VersionChecker/]: [pipez] Starting version check at https://update.maxhenkel.de/neoforge/pipez
[07:13:53] [NeoForge Version Check/INFO] [ne.ne.fm.VersionChecker/]: [pipez] Found status: BETA Current: 1.21.1-1.2.31 Target: 1.21.1-1.2.31
[07:13:53] [NeoForge Version Check/INFO] [ne.ne.fm.VersionChecker/]: [create] Starting version check at https://api.modrinth.com/updates/create/forge_updates.json?neoforge=only
[07:13:53] [NeoForge Version Check/INFO] [ne.ne.fm.VersionChecker/]: [create] Found status: OUTDATED Current: 6.0.10 Target: 6.0.10+mc1.21.1
[07:13:53] [NeoForge Version Check/INFO] [ne.ne.fm.VersionChecker/]: [integrateddynamics] Starting version check at https://raw.githubusercontent.com/CyclopsMC/Versions/master/neoforge_update/integrated-dynamics.json
[07:14:08] [NeoForge Version Check/WARN] [ne.ne.fm.VersionChecker/]: Failed to process update information
java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:917) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133) ~[java.net.http:?] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.openUrlString(VersionChecker.java:129) ~[loader-4.0.44.jar%23161!/:4.0] {}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.process(VersionChecker.java:162) [loader-4.0.44.jar%23161!/:4.0] {}
	at java.base/java.lang.Iterable.forEach(Iterable.java:75) [?:?] {re:mixin}
	at MC-BOOTSTRAP/fml_loader@4.0.44/net.neoforged.fml.VersionChecker$1.run(VersionChecker.java:105) [loader-4.0.44.jar%23161!/:4.0] {}
Caused by: java.net.http.HttpConnectTimeoutException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:68) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
Caused by: java.net.ConnectException: HTTP connect timed out
	at java.net.http/jdk.internal.net.http.ResponseTimerEvent.handle(ResponseTimerEvent.java:69) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl.purgeTimeoutsAndReturnNextDeadline(HttpClientImpl.java:1751) ~[java.net.http:?] {}
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1348) ~[java.net.http:?] {}
[07:19:31] [Render thread/INFO] [co.si.cr.Create/]: Created 371 recipes which will be injected into the game
[07:19:31] [Render thread/INFO] [co.si.cr.Create/]: Created 14 tags which will be injected into the game
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.AutoCraftingTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.SpawnExtraGridTestTools for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.PatternProviderPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.CrystalResonanceGeneratorTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.InterfaceTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.PatternProviderLockModePlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.ItemP2PTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.InvalidPatternTestPlot for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.MemoryCardTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.AnnihilationPlaneTests for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.InscriberTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.GuidebookPlot for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.SubnetPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.P2PTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.QnbTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.ExternalEnergyTestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.ChannelTests for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.TestPlots for plots
[07:19:32] [Worker-Main-25/INFO] [AE2:C/]: Scanning class appeng.server.testplots.SpatialTestPlots for plots
[07:19:33] [Render thread/INFO] [minecraft/RecipeManager]: Loaded 11979 recipes
[07:19:33] [Render thread/ERROR] [minecraft/AdvancementTree]: Couldn't load advancements: [dungeons_arise:find_thornborn_towers, dungeons_arise:find_fishing_hut]
[07:19:33] [Render thread/INFO] [minecraft/AdvancementTree]: Loaded 6720 advancements
[07:19:33] [Render thread/INFO] [Curios API/]: Loaded 16 curio slots
[07:19:33] [Render thread/ERROR] [Curios API/]: example is not a registered slot type!
[07:19:33] [Render thread/INFO] [Curios API/]: Loaded 3 curio entities
[07:19:33] [Render thread/INFO] [ne.by.av.Avaritia/]: Loaded 0 singularities took 3ms
[07:19:33] [Render thread/WARN] [minecraft/MappedRegistry]: Not all defined tags for registry ResourceKey[minecraft:root / minecraft:block] are present in data pack: cataclysm:needs_black_steel_tool, cataclysm:needs_monstrosity_tool
[07:19:36] [Render thread/INFO] [FluxNetworks/]: Released client Flux Networks cache
[07:19:36] [Server thread/INFO] [minecraft/IntegratedServer]: Starting integrated minecraft server version 1.21.1
[07:19:36] [Server thread/INFO] [minecraft/MinecraftServer]: Generating keypair
[07:19:36] [Server thread/INFO] [FluxNetworks/Energy]: Energy blacklist loaded: 0 block entries, 0 item entries
[07:19:36] [Server thread/INFO] [FTB Teams/]: loaded team data: 1 known players, 1 teams total
[07:19:37] [Server thread/INFO] [spark/]: Starting background profiler...
[07:19:37] [Server thread/INFO] [spark/]: The async-profiler engine is not supported for your os/arch (windows11/amd64), so the built-in Java engine will be used instead.
[07:19:37] [Server thread/INFO] [de.ry.sa.Sable/]: Creating physics pipeline for ResourceKey[minecraft:dimension / minecraft:overworld] using RapierPhysicsPipelineProvider
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by dev.ryanhcode.sable.physics.impl.rapier.Rapier3D in module sable_rapier (union:/C:/Users/AI-777hi/AppData/Roaming/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/sable-neoforge-1.21.1-2.0.3.jar%23327_/META-INF/jarjar/dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.3.jar%23455!/)
WARNING: Use --enable-native-access=sable_rapier to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

[2026-08-30T23:19:37.207524100Z] [[32mINFO[0m] (sable_rapier) Rapier scene initialized
[07:19:37] [Server thread/INFO] [de.ry.sa.Sable/]: Creating physics pipeline for ResourceKey[minecraft:dimension / anvilcraft:overworld_like] using RapierPhysicsPipelineProvider
[2026-08-30T23:19:37.499327600Z] [[32mINFO[0m] (sable_rapier) Rapier scene initialized
[07:19:38] [Server thread/INFO] [de.ry.sa.Sable/]: Creating physics pipeline for ResourceKey[minecraft:dimension / minecraft:the_end] using RapierPhysicsPipelineProvider
[2026-08-30T23:19:38.062654900Z] [[32mINFO[0m] (sable_rapier) Rapier scene initialized
[07:19:38] [Server thread/INFO] [de.ry.sa.Sable/]: Creating physics pipeline for ResourceKey[minecraft:dimension / twilightforest:twilight_forest] using RapierPhysicsPipelineProvider
[2026-08-30T23:19:38.250976400Z] [[32mINFO[0m] (sable_rapier) Rapier scene initialized
[07:19:38] [Server thread/INFO] [de.ry.sa.Sable/]: Creating physics pipeline for ResourceKey[minecraft:dimension / minecraft:the_nether] using RapierPhysicsPipelineProvider
[2026-08-30T23:19:38.277684600Z] [[32mINFO[0m] (sable_rapier) Rapier scene initialized
[07:19:38] [Server thread/INFO] [de.ry.sa.Sable/]: Creating physics pipeline for ResourceKey[minecraft:dimension / ae2:spatial_storage] using RapierPhysicsPipelineProvider
[2026-08-30T23:19:38.301426600Z] [[32mINFO[0m] (sable_rapier) Rapier scene initialized
[07:19:38] [Server thread/INFO] [minecraft/MinecraftServer]: Preparing start region for dimension minecraft:overworld
[07:19:38] [Render thread/INFO] [minecraft/LoggerChunkProgressListener]: 准备生成区域中：0%
[07:19:38] [Server thread/INFO] [ne.ne.ne.se.pe.PermissionAPI/]: Successfully initialized permission handler neoforge:default_handler
[07:19:38] [Server thread/INFO] [xa.ma.WorldMap/]: Registered synced player tracker system: ftb_teams
[07:19:38] [Render thread/INFO] [minecraft/LoggerChunkProgressListener]: Time elapsed: 308 ms
[07:19:38] [Server thread/INFO] [xa.hu.mi.MinimapLogs/]: Registered synced player tracker system: ftb_teams
[07:19:38] [Server thread/INFO] [co.qi.li.LivingItem/]: HELLO from server starting
[07:19:38] [Server thread/INFO] [Observable/]: Registered thread Server thread
[07:19:38] [Server thread/INFO] [co.qi.li.li.tr.ContainerCompatibilityConfig/]: Initialized 6 default container compatibility rules
[07:19:38] [Server thread/INFO] [minecraft/IntegratedServer]: Changing view distance to 32, from 10
[07:19:38] [Server thread/INFO] [minecraft/IntegratedServer]: Changing simulation distance to 7, from 0
[07:19:39] [Render thread/INFO] [de.ry.sa.Sable/]: Adding local UDP server channel future
[07:19:39] [Render thread/WARN] [io.ne.bo.Bootstrap/]: Unknown channel option 'SO_BROADCAST' for channel '[id: 0xed5a825a]'
[07:19:39] [Netty Server IO #1/INFO] [de.ry.sa.Sable/]: Server UDP channel active
[07:19:39] [Render thread/INFO] [de.ry.sa.Sable/]: Starting local client UDP channel future
[07:19:39] [Netty Local Client IO #1/INFO] [de.ry.sa.Sable/]: Client UDP channel active
[07:19:39] [Render thread/INFO] [co.si.cr.Create/]: Created 371 recipes which will be injected into the game
[07:19:39] [Render thread/INFO] [co.si.cr.Create/]: Created 14 tags which will be injected into the game
[07:19:39] [Server thread/INFO] [de.an.li.v2.rp.RpcRegistry/]: Scan complete - 35 @RemoteCallable method(s) registered.
[07:19:39] [Server thread/INFO] [minecraft/PlayerList]: 777_hi[local:E:67b063fd] logged in with entity id 20 at (-100.03731246413359, 131.0, -49.78901650391272)
[07:19:39] [Server thread/INFO] [de.ry.sa.Sable/]: Beginning attempted authentication with player 777_hi
[07:19:39] [Render thread/INFO] [xa.ma.WorldMap/]: Fullscreen map required item set to nothing.
[07:19:39] [Render thread/INFO] [xa.ma.WorldMap/]: New world map session initialized!
[07:19:39] [Render thread/INFO] [xa.hu.mi.MinimapLogs/]: Minimap required item set to nothing.
[07:19:39] [Render thread/INFO] [xa.hu.mi.MinimapLogs/]: New Xaero hud session initialized!
[07:19:39] [Render thread/INFO] [Iris/]: Reloading pipeline on dimension change: minecraft:overworld => minecraft:overworld
[07:19:39] [Render thread/INFO] [Iris/]: Destroying pipeline minecraft:overworld
[07:19:39] [Render thread/INFO] [Iris/]: Creating pipeline for dimension minecraft:overworld
[07:19:39] [Server thread/INFO] [minecraft/MinecraftServer]: 777_hi加入了游戏
[07:19:39] [Render thread/INFO] [ChunkBuilder/]: Started 10 worker threads
[07:19:40] [Render thread/INFO] [flywheel/]: Started 10 worker threads
[07:19:40] [Server thread/INFO] [actuallyadditions/]: Sending Player Data to player literal{777_hi} with UUID 011bbfcb-8c4d-456f-b286-efb9ddaae5da.
[07:19:40] [Server thread/ERROR] [ne.ne.bu.EventBus/EVENTBUS]: Exception caught during firing event: Index 2 out of bounds for length 2
	Index: 9
	Listeners:
		0: net.neoforged.bus.EventListenerFactory$event/0x0000000040cd0000@64326a8a
		1: net.neoforged.bus.EventListenerFactory$preServerTick/0x0000000040b42000@2aed5b9b
		2: com.refinedmods.refinedstorage.neoforge.ModInitializer$$Lambda/0x0000000041ead000@79248d90
		3: net.neoforged.bus.EventListenerFactory$serverTick/0x0000000041f24000@7f696174
		4: appeng.hooks.ticking.TickHandler$$Lambda/0x0000000042877c00@7e81a0ad
		5: net.neoforged.bus.EventListenerFactory$serverTickEvent/0x0000000042a5d000@1e4536cc
		6: net.neoforged.bus.EventListenerFactory$onTick/0x0000000042b98000@695469b4
		7: net.neoforged.bus.EventListenerFactory$serverTickPre/0x0000000042d41c00@2925d686
		8: net.neoforged.bus.EventListenerFactory$onTick/0x0000000043812000@1df37017
		9: net.neoforged.bus.EventListenerFactory$onServerTick/0x0000000043a80800@2a37c633
		10: com.hrznstudio.titanium.event.handler.EventManager$FilteredEventManager$$Lambda/0x0000000040c02c00@61d9c608
		11: net.neoforged.bus.EventListenerFactory$onTickPre/0x00000000451a0800@60e40308
		12: net.neoforged.bus.EventListenerFactory$onTickStart/0x00000000463a8000@67727874
		13: net.neoforged.bus.EventListenerFactory$onTickStart/0x00000000463a8400@6107cf45
java.lang.IndexOutOfBoundsException: Index 2 out of bounds for length 2
	at java.base/jdk.internal.util.Preconditions.outOfBounds(Preconditions.java:100)
	at java.base/jdk.internal.util.Preconditions.outOfBoundsCheckIndex(Preconditions.java:106)
	at java.base/jdk.internal.util.Preconditions.checkIndex(Preconditions.java:302)
	at java.base/java.util.Objects.checkIndex(Objects.java:365)
	at java.base/java.util.ArrayList.get(ArrayList.java:428)
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.ChannelState.path(ChannelState.java:41)
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.buildTelemetry(LivingWaxedCopperFunction.java:225)
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.tickContainerData(LivingWaxedCopperFunction.java:129)
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.container.ContainerLivingItemHandler.processContext(ContainerLivingItemHandler.java:422)
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer(ContainerLivingItemHandler.java:266)
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.LivingItem.onServerTick(LivingItem.java:265)
	at MC-BOOTSTRAP/net.neoforged.bus/net.neoforged.bus.EventBus.post(EventBus.java:360)
	at MC-BOOTSTRAP/net.neoforged.bus/net.neoforged.bus.EventBus.post(EventBus.java:328)
	at TRANSFORMER/neoforge@21.1.249/net.neoforged.neoforge.event.EventHooks.fireServerTickPre(EventHooks.java:1010)
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:915)
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.client.server.IntegratedServer.tickServer(IntegratedServer.java:110)
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:707)
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.lambda$spin$2(MinecraftServer.java:267)
	at java.base/java.lang.Thread.run(Thread.java:1474)

[07:19:40] [Server thread/ERROR] [minecraft/MinecraftServer]: Encountered an unexpected exception
java.lang.IndexOutOfBoundsException: Index 2 out of bounds for length 2
	at java.base/jdk.internal.util.Preconditions.outOfBounds(Preconditions.java:100) ~[?:?] {}
	at java.base/jdk.internal.util.Preconditions.outOfBoundsCheckIndex(Preconditions.java:106) ~[?:?] {}
	at java.base/jdk.internal.util.Preconditions.checkIndex(Preconditions.java:302) ~[?:?] {}
	at java.base/java.util.Objects.checkIndex(Objects.java:365) ~[?:?] {re:mixin}
	at java.base/java.util.ArrayList.get(ArrayList.java:428) ~[?:?] {re:mixin}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.ChannelState.path(ChannelState.java:41) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.buildTelemetry(LivingWaxedCopperFunction.java:225) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.tickContainerData(LivingWaxedCopperFunction.java:129) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.container.ContainerLivingItemHandler.processContext(ContainerLivingItemHandler.java:422) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:mixin,re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer(ContainerLivingItemHandler.java:266) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:mixin,re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.LivingItem.onServerTick(LivingItem.java:265) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:classloading}
	at MC-BOOTSTRAP/net.neoforged.bus/net.neoforged.bus.EventBus.post(EventBus.java:360) ~[bus-8.0.5.jar%23164!/:?] {}
	at MC-BOOTSTRAP/net.neoforged.bus/net.neoforged.bus.EventBus.post(EventBus.java:328) ~[bus-8.0.5.jar%23164!/:?] {}
	at TRANSFORMER/neoforge@21.1.249/net.neoforged.neoforge.event.EventHooks.fireServerTickPre(EventHooks.java:1010) ~[neoforge-21.1.249-universal.jar%23258!/:?] {re:mixin,re:classloading}
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:915) ~[client-1.21.1-20240808.144430-srg.jar%23257!/:?] {re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.client.server.IntegratedServer.tickServer(IntegratedServer.java:110) ~[client-1.21.1-20240808.144430-srg.jar%23257!/:?] {re:mixin,pl:runtimedistcleaner:A,re:classloading,pl:mixin:APP:sable.mixins.json:toast.IntegratedServerMixin from mod sable,pl:mixin:A,pl:runtimedistcleaner:A}
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:707) [client-1.21.1-20240808.144430-srg.jar%23257!/:?] {re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.lambda$spin$2(MinecraftServer.java:267) [client-1.21.1-20240808.144430-srg.jar%23257!/:?] {re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at java.base/java.lang.Thread.run(Thread.java:1474) [?:?] {re:mixin}
[07:19:40] [Server thread/FATAL] [ne.ne.ne.co.NeoForgeMod/]: Preparing crash report with UUID 68e359d2-f15e-4d4d-954e-dc1bcd57d706
[07:19:40] [Server thread/ERROR] [minecraft/MinecraftServer]: This crash report has been saved to: C:\Users\AI-777hi\AppData\Roaming\.minecraft\versions\1.21.1-NeoForge_21.1.249\crash-reports\crash-2026-08-31_07.19.40-server.txt
[07:19:40] [Server thread/INFO] [minecraft/MinecraftServer]: Stopping server
[07:19:40] [Server thread/INFO] [minecraft/MinecraftServer]: Saving players
[07:19:40] [Server thread/INFO] [minecraft/MinecraftServer]: Saving worlds
[07:19:40] [Netty Local Client IO #1/INFO] [de.ry.sa.Sable/]: Client UDP channel inactive
[07:19:40] [Netty Local Client IO #1/INFO] [de.ry.sa.Sable/]: Closed UDP channel!
[07:19:40] [Server thread/INFO] [minecraft/MinecraftServer]: Saving chunks for level 'ServerLevel[77]'/minecraft:overworld
[07:19:40] [Server thread/INFO] [de.ry.sa.Sable/]: Saving sub-levels for level 'ServerLevel[77]'/minecraft:overworld
[07:19:40] [Render thread/INFO] [Veil/]: Multi-Bind supported, using core
[07:19:40] [Server thread/INFO] [minecraft/MinecraftServer]: Saving chunks for level 'ServerLevel[77]'/anvilcraft:overworld_like
[07:19:40] [Server thread/INFO] [de.ry.sa.Sable/]: Saving sub-levels for level 'ServerLevel[77]'/anvilcraft:overworld_like
[07:19:40] [Render thread/INFO] [xa.hu.mi.MinimapLogs/]: Reloading radar icon resources...
[07:19:40] [Render thread/INFO] [xa.hu.mi.MinimapLogs/]: Reloaded radar icon resources!
[07:19:40] [Server thread/INFO] [minecraft/MinecraftServer]: Saving chunks for level 'ServerLevel[77]'/minecraft:the_end
[07:19:40] [Server thread/INFO] [de.ry.sa.Sable/]: Saving sub-levels for level 'ServerLevel[77]'/minecraft:the_end
[07:19:40] [Render thread/FATAL] [ne.ne.ne.co.NeoForgeMod/]: Preparing crash report with UUID df0daf7c-3f69-4c7b-9cca-f0a94afacdb9
---- Minecraft Crash Report ----

// *plays dead*
Please make sure this issue is not caused by Sable before reporting it to other mod authors.
If you cannot reproduce it without Sable, file a report on the issue tracker
https://github.com/ryanhcode/sable/issues

// Uh... Did I do that?

Time: 2026-08-31 07:19:40
Description: Exception in server tick loop

java.lang.IndexOutOfBoundsException: Index 2 out of bounds for length 2
	at java.base/jdk.internal.util.Preconditions.outOfBounds(Preconditions.java:100) ~[?:?] {}
	at java.base/jdk.internal.util.Preconditions.outOfBoundsCheckIndex(Preconditions.java:106) ~[?:?] {}
	at java.base/jdk.internal.util.Preconditions.checkIndex(Preconditions.java:302) ~[?:?] {}
	at java.base/java.util.Objects.checkIndex(Objects.java:365) ~[?:?] {re:mixin}
	at java.base/java.util.ArrayList.get(ArrayList.java:428) ~[?:?] {re:mixin}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.ChannelState.path(ChannelState.java:41) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.buildTelemetry(LivingWaxedCopperFunction.java:225) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.tickContainerData(LivingWaxedCopperFunction.java:129) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.container.ContainerLivingItemHandler.processContext(ContainerLivingItemHandler.java:422) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:mixin,re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.living.container.ContainerLivingItemHandler.processContainer(ContainerLivingItemHandler.java:266) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:mixin,re:classloading}
	at TRANSFORMER/living_item@1.3.2/com.qiqi.li.LivingItem.onServerTick(LivingItem.java:265) ~[living_item-1.21.1-1.3.2.jar%23322!/:?] {re:classloading}
	at MC-BOOTSTRAP/net.neoforged.bus/net.neoforged.bus.EventBus.post(EventBus.java:360) ~[bus-8.0.5.jar%23164!/:?] {}
	at MC-BOOTSTRAP/net.neoforged.bus/net.neoforged.bus.EventBus.post(EventBus.java:328) ~[bus-8.0.5.jar%23164!/:?] {}
	at TRANSFORMER/neoforge@21.1.249/net.neoforged.neoforge.event.EventHooks.fireServerTickPre(EventHooks.java:1010) ~[neoforge-21.1.249-universal.jar%23258!/:?] {re:mixin,re:classloading}
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:915) ~[client-1.21.1-20240808.144430-srg.jar%23257!/:?] {re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.client.server.IntegratedServer.tickServer(IntegratedServer.java:110) ~[client-1.21.1-20240808.144430-srg.jar%23257!/:?] {re:mixin,pl:runtimedistcleaner:A,re:classloading,pl:mixin:APP:sable.mixins.json:toast.IntegratedServerMixin from mod sable,pl:mixin:A,pl:runtimedistcleaner:A}
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:707) ~[client-1.21.1-20240808.144430-srg.jar%23257!/:?] {re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.lambda$spin$2(MinecraftServer.java:267) ~[client-1.21.1-20240808.144430-srg.jar%23257!/:?] {re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at java.base/java.lang.Thread.run(Thread.java:1474) ~[?:?] {re:mixin}


A detailed walkthrough of the error, its code path and all known details is as follows:
---------------------------------------------------------------------------------------

-- System Details --
Details:
	Minecraft Version: 1.21.1
	Minecraft Version ID: 1.21.1
	Operating System: Windows 11 (amd64) version 10.0
	Java Version: 25.0.3, Microsoft
	Java VM Version: OpenJDK 64-Bit Server VM (mixed mode, sharing), Microsoft
	Memory: 736712352 bytes (702 MiB) / 3187671040 bytes (3040 MiB) up to 7516192768 bytes (7168 MiB)
	CPUs: 24
	Processor Vendor: AuthenticAMD
	Processor Name: AMD Ryzen AI 9 HX 370 w/ Radeon 890M           
	Identifier: AuthenticAMD Family 26 Model 36 Stepping 0
	Microarchitecture: unknown
	Frequency (GHz): 2.00
	Number of physical packages: 1
	Number of physical CPUs: 12
	Number of logical CPUs: 24
	Graphics card #0 name: AMD Radeon(TM) 890M Graphics
	Graphics card #0 vendor: Advanced Micro Devices, Inc.
	Graphics card #0 VRAM (MiB): 2048.00
	Graphics card #0 deviceId: VideoController1
	Graphics card #0 versionInfo: 32.0.13046.16
	Graphics card #1 name: NVIDIA GeForce RTX 5060 Ti
	Graphics card #1 vendor: NVIDIA
	Graphics card #1 VRAM (MiB): 16311.00
	Graphics card #1 deviceId: VideoController2
	Graphics card #1 versionInfo: 32.0.16.1074
	Memory slot #0 capacity (MiB): 16384.00
	Memory slot #0 clockSpeed (GHz): 5.60
	Memory slot #0 type: Unknown
	Memory slot #1 capacity (MiB): 16384.00
	Memory slot #1 clockSpeed (GHz): 5.60
	Memory slot #1 type: Unknown
	Virtual memory max (MiB): 60024.86
	Virtual memory used (MiB): 44794.95
	Swap memory total (MiB): 29696.00
	Swap memory used (MiB): 2527.50
	Space in storage for jna.tmpdir (MiB): available: 268471.50, total: 951590.00
	Space in storage for org.lwjgl.system.SharedLibraryExtractPath (MiB): available: 268471.50, total: 951590.00
	Space in storage for io.netty.native.workdir (MiB): available: 268471.50, total: 951590.00
	Space in storage for java.io.tmpdir (MiB): available: 268471.50, total: 951590.00
	Space in storage for workdir (MiB): available: 268471.50, total: 951590.00
	JVM Flags: 13 total; -XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump -XX:-OmitStackTraceInFastThrow -Xmx7168m -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M -XX:MaxGCPauseMillis=50 -XX:+PerfDisableSharedMem -XX:MinHeapFreeRatio=25 -XX:MaxHeapFreeRatio=40
	Loaded Shaderpack: (off)
	Server Running: true
	Player Count: 1 / 8; [ServerPlayer['777_hi'/20, l='ServerLevel[77]', x=-100.04, y=131.00, z=-49.79]]
	Active Data Packs: create:dynamic_data, vanilla, mod_data, mod/sodium, mod/cyclopscore (incompatible), mod/kuma_api (incompatible), mod/fabric_renderer_api_v1, mod/sodium_extra, mod/geckolib, mod/aeronautics_bundled, mod/anvillib_config, mod/sophisticatedcore (incompatible), mod/goety (incompatible), mod/xaeroworldmap (incompatible), mod/neoforge, mod/fabric_block_view_api_v2, mod/ageratum, mod/iris, mod/veil (incompatible), mod/sophisticatedbackpacks (incompatible), mod/simulated (incompatible), mod/anvillib_collision, mod/nuggets (incompatible), mod/balm (incompatible), mod/anvillib_util, mod/cloth_config (incompatible), mod/twilightforest, mod/refinedstorage (incompatible), mod/industrialforegoing (incompatible), mod/commoncapabilities (incompatible), mod/blur, mod/anvillib_integration, mod/lionfishapi (incompatible), mod/spark (incompatible), mod/actuallyadditions (incompatible), mod/curios (incompatible), mod/cataclysm (incompatible), mod/integratedtunnels,integratedtunnelscompat (incompatible), mod/anvillib_registrum, mod/anvillib_explosion, mod/architectury (incompatible), mod/transition (incompatible), mod/fabric_rendering_data_attachment_v1, mod/infiniverse, mod/anvillib_recipe, mod/observable (incompatible), mod/anvillib_multiblock, mod/anvillib_rpc, mod/inventorysorter (incompatible), mod/ftblibrary (incompatible), mod/ftbteams (incompatible), mod/anvillib_moveable_entity_block, mod/customskinloader (incompatible), mod/jei (incompatible), mod/aeronautics (incompatible), mod/xaerolib (incompatible), mod/trender (incompatible), mod/mekanism, mod/mekanismgenerators, mod/anvillib_space_select, mod/waystones (incompatible), mod/anvillib_network, mod/anvillib_sync, mod/naturescompass (incompatible), mod/midnightlib, mod/mcjtylib, mod/rftoolsbase, mod/xnet, mod/guideme, mod/explorerscompass (incompatible), mod/waveycapes (incompatible), mod/anvillib, mod/resourcify (incompatible), mod/anvillib_codec, mod/ars_nouveau (incompatible), mod/ftbchunks (incompatible), mod/offroad (incompatible), mod/anvilcraft (incompatible), mod/ironchest, mod/dungeons_arise, mod/fabric_api_base, mod/mousetweaks (incompatible), mod/titanium, mod/avaritia, mod/jade (incompatible), mod/ae2 (incompatible), mod/kotlinforforge (incompatible), mod/pipez (incompatible), mod/sablecompanion, mod/flywheel (incompatible), mod/ponder (incompatible), mod/create (incompatible), mod/sable, mod/living_item, mod/fxntstorage, mod/create_aeronautics_toolgun, mod/integrateddynamics,integrateddynamicscompat (incompatible), mod/xaerominimap (incompatible), mod/fluxnetworks (incompatible), mod/wip (incompatible), mod/storagedrawers, mod/anvillib_font, mod/lambdynlights_api, mod/componentviewer, mod/anvillib_wheel, mod/createaddition (incompatible)
	Available Data Packs: bundle, trade_rebalance, vanilla, mod/actuallyadditions (incompatible), mod/ae2 (incompatible), mod/aeronautics (incompatible), mod/aeronautics_bundled, mod/ageratum, mod/anvilcraft (incompatible), mod/anvillib, mod/anvillib_codec, mod/anvillib_collision, mod/anvillib_config, mod/anvillib_explosion, mod/anvillib_font, mod/anvillib_integration, mod/anvillib_moveable_entity_block, mod/anvillib_multiblock, mod/anvillib_network, mod/anvillib_recipe, mod/anvillib_registrum, mod/anvillib_rpc, mod/anvillib_space_select, mod/anvillib_sync, mod/anvillib_util, mod/anvillib_wheel, mod/architectury (incompatible), mod/ars_nouveau (incompatible), mod/avaritia, mod/balm (incompatible), mod/blur, mod/cataclysm (incompatible), mod/cloth_config (incompatible), mod/commoncapabilities (incompatible), mod/componentviewer, mod/create (incompatible), mod/create_aeronautics_toolgun, mod/createaddition (incompatible), mod/curios (incompatible), mod/customskinloader (incompatible), mod/cyclopscore (incompatible), mod/dungeons_arise, mod/explorerscompass (incompatible), mod/fabric_api_base, mod/fabric_block_view_api_v2, mod/fabric_renderer_api_v1, mod/fabric_rendering_data_attachment_v1, mod/fluxnetworks (incompatible), mod/flywheel (incompatible), mod/ftbchunks (incompatible), mod/ftblibrary (incompatible), mod/ftbteams (incompatible), mod/fxntstorage, mod/geckolib, mod/goety (incompatible), mod/guideme, mod/industrialforegoing (incompatible), mod/infiniverse, mod/integrateddynamics,integrateddynamicscompat (incompatible), mod/integratedtunnels,integratedtunnelscompat (incompatible), mod/inventorysorter (incompatible), mod/iris, mod/ironchest, mod/jade (incompatible), mod/jei (incompatible), mod/kotlinforforge (incompatible), mod/kuma_api (incompatible), mod/lambdynlights_api, mod/lionfishapi (incompatible), mod/living_item, mod/mcjtylib, mod/mekanism, mod/mekanismgenerators, mod/midnightlib, mod/mousetweaks (incompatible), mod/naturescompass (incompatible), mod/neoforge, mod/nuggets (incompatible), mod/observable (incompatible), mod/offroad (incompatible), mod/pipez (incompatible), mod/ponder (incompatible), mod/refinedstorage (incompatible), mod/resourcify (incompatible), mod/rftoolsbase, mod/sable, mod/sablecompanion, mod/simulated (incompatible), mod/sodium, mod/sodium_extra, mod/sophisticatedbackpacks (incompatible), mod/sophisticatedcore (incompatible), mod/spark (incompatible), mod/storagedrawers, mod/titanium, mod/transition (incompatible), mod/trender (incompatible), mod/twilightforest, mod/veil (incompatible), mod/waveycapes (incompatible), mod/waystones (incompatible), mod/wip (incompatible), mod/xaerolib (incompatible), mod/xaerominimap (incompatible), mod/xaeroworldmap (incompatible), mod/xnet, mod_data, mod/anvilcraft:resourcepacks/first_ancient_debris, create:dynamic_data
	Enabled Feature Flags: minecraft:vanilla
	World Generation: Experimental
	World Seed: -2032795982907864146
	Type: Integrated Server (map_client.txt)
	Is Modded: Definitely; Client brand changed to 'neoforge'; Server brand changed to 'neoforge'
	Launched Version: 1.21.1-NeoForge_21.1.249
	ModLauncher: 11.0.5+main.901c6ea8
	ModLauncher launch target: forgeclient
	ModLauncher services: 
		sponge-mixin-0.15.2+mixin.0.8.7.jar mixin PLUGINSERVICE 
		loader-4.0.44.jar slf4jfixer PLUGINSERVICE 
		loader-4.0.44.jar runtime_enum_extender PLUGINSERVICE 
		at-modlauncher-10.0.1.jar accesstransformer PLUGINSERVICE 
		loader-4.0.44.jar runtimedistcleaner PLUGINSERVICE 
		modlauncher-11.0.5.jar mixin TRANSFORMATIONSERVICE 
		modlauncher-11.0.5.jar fml TRANSFORMATIONSERVICE 
		modlauncher-11.0.5.jar customskinloader-bootstrap TRANSFORMATIONSERVICE 
	FML Language Providers: 
		kotlinforforge@5.12.0
		javafml@4.0
		lowcodefml@4.0
		minecraft@4.0
	Mod List: 
		[实用拓展] actuallyadditions-1.3.26+mc1.21.1.jar      |Actually Additions            |actuallyadditions             |1.3.26              |Manifest: NOSIGNATURE
		ageratum-neoforge-1.21.1-0.0.1+build.111.jar      |Ageratum                      |ageratum                      |0.0.1+build.111     |Manifest: NOSIGNATURE
		[铁砧工艺] anvilcraft-neoforge-1.21.1-1.6.0+snapshot.2|AnvilCraft                    |anvilcraft                    |1.6.0+snapshot.2179 |Manifest: NOSIGNATURE
		anvillib-neoforge-1.21.1-2.0.0+snapshot.513.jar   |AnvilLib                      |anvillib                      |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-codec-neoforge-1.21.1-2.0.0+snapshot.513.|AnvilLib-Codec                |anvillib_codec                |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-collision-neoforge-1.21.1-2.0.0+snapshot.|AnvilLib-Collision            |anvillib_collision            |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-config-neoforge-1.21.1-2.0.0+snapshot.513|AnvilLib-Config               |anvillib_config               |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-explosion-neoforge-1.21.1-2.0.0+snapshot.|AnvilLib-Explosion            |anvillib_explosion            |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-font-neoforge-1.21.1-2.0.0+snapshot.513.j|AnvilLib-Font                 |anvillib_font                 |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-integration-neoforge-1.21.1-2.0.0+snapsho|AnvilLib-Integration          |anvillib_integration          |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-moveable-entity-block-neoforge-1.21.1-2.0|AnvilLib-MoveableEntityBlock  |anvillib_moveable_entity_block|2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-multiblock-neoforge-1.21.1-2.0.0+snapshot|AnvilLib-Multiblock           |anvillib_multiblock           |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-network-neoforge-1.21.1-2.0.0+snapshot.51|AnvilLib-Network              |anvillib_network              |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-recipe-neoforge-1.21.1-2.0.0+snapshot.513|AnvilLib-Recipe               |anvillib_recipe               |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-registrum-neoforge-1.21.1-2.0.0+snapshot.|AnvilLib-Registrum            |anvillib_registrum            |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-rpc-neoforge-1.21.1-2.0.0+snapshot.513.ja|AnvilLib-RPC                  |anvillib_rpc                  |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-space-select-neoforge-1.21.1-2.0.0+snapsh|AnvilLib-Space-Select         |anvillib_space_select         |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-sync-neoforge-1.21.1-2.0.0+snapshot.513.j|AnvilLib-Sync                 |anvillib_sync                 |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-util-neoforge-1.21.1-2.0.0+snapshot.513.j|AnvilLib-Util                 |anvillib_util                 |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		anvillib-wheel-neoforge-1.21.1-2.0.0+snapshot.513.|AnvilLib-Wheel                |anvillib_wheel                |2.0.0+snapshot.513  |Manifest: NOSIGNATURE
		[应用能源2] appliedenergistics2-19.2.17.jar           |Applied Energistics 2         |ae2                           |19.2.17             |Manifest: NOSIGNATURE
		architectury-13.0.8-neoforge.jar                  |Architectury                  |architectury                  |13.0.8              |Manifest: NOSIGNATURE
		[新生魔艺] ars_nouveau-1.21.1-5.12.1.jar              |Ars Nouveau                   |ars_nouveau                   |5.12.1              |Manifest: NOSIGNATURE
		[无尽贪婪Neo] AvaritiaNeo-1.21-1.3.1.jar              |Avaritia                      |avaritia                      |1.3.1               |Manifest: NOSIGNATURE
		balm-neoforge-1.21.1-21.0.63.jar                  |Balm                          |balm                          |21.0.63             |Manifest: NOSIGNATURE
		[界面背景模糊] blur-neoforge-6.3.1+1.21.1.jar           |Blur+                         |blur                          |6.3.1               |Manifest: NOSIGNATURE
		cloth-config-15.0.140-neoforge.jar                |Cloth Config v15 API          |cloth_config                  |15.0.140            |Manifest: NOSIGNATURE
		commoncapabilities-1.21.1-neoforge-2.11.5-363.jar |CommonCapabilities            |commoncapabilities            |2.11.5              |Manifest: NOSIGNATURE
		componentviewer-neoforge-1.3.3+1.21.1.jar         |Component Viewer              |componentviewer               |1.3.3+1.21.1        |Manifest: NOSIGNATURE
		[机械动力] create-1.21.1-6.0.10.jar                   |Create                        |create                        |6.0.10              |Manifest: NOSIGNATURE
		[机械动力：航空学] create-aeronautics-bundled-1.21.1-1.3.0|Create Aeronautics            |aeronautics_bundled           |1.3.0               |Manifest: NOSIGNATURE
		dev.eriksonn.aeronautics.aeronautics-neoforge-1.21|Create Aeronautics            |aeronautics                   |1.3.0               |Manifest: NOSIGNATURE
		[机械动力航空学：工具枪] create_aeronautics_toolgun-0.3.4.jar|Create Aeronautics: Toolgun   |create_aeronautics_toolgun    |0.3.4               |Manifest: NOSIGNATURE
		[机械动力：创想附加] createaddition-1.7.0.jar              |Create Crafts & Additions     |createaddition                |1.7.0               |Manifest: NOSIGNATURE
		dev.ryanhcode.offroad.offroad-neoforge-1.21.1-1.3.|Create Offroad                |offroad                       |1.3.0               |Manifest: NOSIGNATURE
		dev.simulated_team.simulated.simulated-neoforge-1.|Create Simulated              |simulated                     |1.3.0               |Manifest: NOSIGNATURE
		fxntstorage-1.3.3+mc-1.21.1-neoforge.jar          |Create: Storage               |fxntstorage                   |1.3.3               |Manifest: NOSIGNATURE
		curios-neoforge-9.5.1+1.21.1.jar                  |Curios API                    |curios                        |9.5.1+1.21.1        |Manifest: NOSIGNATURE
		CustomSkinLoader-Common.jar                       |CustomSkinLoader              |customskinloader              |15.0.1              |Manifest: NOSIGNATURE
		cyclopscore-1.21.1-neoforge-1.29.3.jar            |Cyclops Core                  |cyclopscore                   |1.29.3              |Manifest: NOSIGNATURE
		[探险者指南针] ExplorersCompass-1.21.1-3.4.0-neoforge.ja|Explorer's Compass            |explorerscompass              |1.21.1-3.4.0-neoforg|Manifest: NOSIGNATURE
		[通量网络] FluxNetworks-1.21.1-8.0.0.jar              |Flux Networks                 |fluxnetworks                  |8.0.0               |Manifest: NOSIGNATURE
		flywheel-neoforge-1.21.1-1.0.6.jar                |Flywheel                      |flywheel                      |1.0.6               |Manifest: NOSIGNATURE
		fabric-api-base-0.4.42+d1308ded19.jar             |Forgified Fabric API Base     |fabric_api_base               |0.4.42+d1308ded19   |Manifest: NOSIGNATURE
		fabric-block-view-api-v2-1.0.10+9afaaf8c19.jar    |Forgified Fabric BlockView API|fabric_block_view_api_v2      |1.0.10+9afaaf8c19   |Manifest: NOSIGNATURE
		fabric-renderer-api-v1-3.4.1+9125b6dc19.jar       |Forgified Fabric Renderer API |fabric_renderer_api_v1        |3.4.1+9125b6dc19    |Manifest: NOSIGNATURE
		fabric-rendering-data-attachment-v1-0.3.48+73761d2|Forgified Fabric Rendering Dat|fabric_rendering_data_attachme|0.3.48+73761d2e19   |Manifest: NOSIGNATURE
		[FTB 区块] ftb-chunks-neoforge-2101.1.20.jar        |FTB Chunks                    |ftbchunks                     |2101.1.20           |Manifest: NOSIGNATURE
		ftb-library-neoforge-2101.1.33.jar                |FTB Library                   |ftblibrary                    |2101.1.33           |Manifest: NOSIGNATURE
		[FTB 团队] ftb-teams-neoforge-2101.1.10.jar         |FTB Teams                     |ftbteams                      |2101.1.10           |Manifest: NOSIGNATURE
		geckolib-neoforge-1.21.1-4.9.2.jar                |GeckoLib 4                    |geckolib                      |4.9.2               |Manifest: NOSIGNATURE
		[诡厄巫法] goety-3.1.4.jar                            |Goety                         |goety                         |3.1.4               |Manifest: NOSIGNATURE
		guideme-21.1.17.jar                               |GuideME                       |guideme                       |21.1.17             |Manifest: NOSIGNATURE
		[工业先锋] industrialforegoing-1.21-3.6.39.jar        |Industrial Foregoing          |industrialforegoing           |1.21-3.6.39         |Manifest: NOSIGNATURE
		infiniverse-568341-5486311.jar                    |Infiniverse                   |infiniverse                   |2.0.1.0             |Manifest: NOSIGNATURE
		[动态联合／集成动力] integrateddynamics-1.21.1-neoforge-1.3|IntegratedDynamics            |integrateddynamics            |1.35.0              |Manifest: NOSIGNATURE
		[集成管道] integratedtunnels-1.21.1-neoforge-1.10.0-73|IntegratedTunnels             |integratedtunnels             |1.10.0              |Manifest: NOSIGNATURE
		iris-neoforge-1.8.14-beta.1+mc1.21.1.jar          |Iris                          |iris                          |1.8.14-beta.1+mc1.21|Manifest: NOSIGNATURE
		[更多箱子] ironchest-1.21-neoforge-16.0.7.jar         |Iron Chests                   |ironchest                     |1.21-neoforge-16.0.7|Manifest: NOSIGNATURE
		[玉 🔍] Jade-1.21.1-NeoForge-15.10.5.jar           |Jade                          |jade                          |15.10.5+neoforge    |Manifest: NOSIGNATURE
		[JEI物品管理器] jei-1.21.1-neoforge-19.38.0.366.jar    |Just Enough Items             |jei                           |19.38.0.366         |Manifest: NOSIGNATURE
		thedarkcolour.kffmod-5.12.0.jar                   |Kotlin For Forge              |kotlinforforge                |5.12.0              |Manifest: NOSIGNATURE
		kuma-api-neoforge-21.0.8+1.21.jar                 |KumaAPI                       |kuma_api                      |21.0.8              |Manifest: NOSIGNATURE
		[灾变] L_Ender's Cataclysm 1.21.1-3.32.jar          |L_Ender's Cataclysm 1.21.1    |cataclysm                     |3.32                |Manifest: NOSIGNATURE
		lambdynamiclights-api-4.5.1+1.21.1-mojmap.jar     |LambDynamicLights (API)       |lambdynlights_api             |4.5.1+1.21.1        |Manifest: NOSIGNATURE
		lionfishapi-3.1.jar                               |lionfishapi                   |lionfishapi                   |3.1                 |Manifest: NOSIGNATURE
		living_item-1.21.1-1.3.2.jar                      |Living Item                   |living_item                   |1.3.2               |Manifest: NOSIGNATURE
		mcjtylib-1.21-9.0.21.jar                          |McJtyLib                      |mcjtylib                      |1.21-9.0.21         |Manifest: NOSIGNATURE
		[通用机械] Mekanism-1.21.1-10.7.19.85.jar             |Mekanism                      |mekanism                      |10.7.19             |Manifest: NOSIGNATURE
		[通用机械发电机] MekanismGenerators-1.21.1-10.7.19.85.jar|Mekanism: Generators          |mekanismgenerators            |10.7.19             |Manifest: NOSIGNATURE
		midnightlib-neoforge-1.9.3+1.21.1.jar             |MidnightLib                   |midnightlib                   |1.9.3               |Manifest: NOSIGNATURE
		client-1.21.1-20240808.144430-srg.jar             |Minecraft                     |minecraft                     |1.21.1              |Manifest: a1:d4:5e:04:4f:d3:d6:e0:7b:37:97:cf:77:b0:de:ad:4a:47:ce:8c:96:49:5f:0a:cf:8c:ae:b2:6d:4b:8a:3f
		[鼠标手势] MouseTweaks-neoforge-mc1.21-2.26.1.jar     |Mouse Tweaks                  |mousetweaks                   |2.26.1              |Manifest: NOSIGNATURE
		[自然罗盘／生物群系指南针] NaturesCompass-1.21.1-3.4.0-neoforg|Nature's Compass              |naturescompass                |1.21.1-3.4.0-neoforg|Manifest: NOSIGNATURE
		neoforge-21.1.249-universal.jar                   |NeoForge                      |neoforge                      |21.1.249            |Manifest: NOSIGNATURE
		nuggets-neoforge-1.21.1-1.1.0.48.jar              |Nuggets                       |nuggets                       |1.1.0.48            |Manifest: NOSIGNATURE
		observable-5.4.4.jar                              |Observable                    |observable                    |5.4.4               |Manifest: NOSIGNATURE
		pipez-neoforge-1.21.1-1.2.31.jar                  |Pipez                         |pipez                         |1.21.1-1.2.31       |Manifest: NOSIGNATURE
		ponder-neoforge-1.0.82+mc1.21.1.jar               |Ponder                        |ponder                        |1.0.82+mc1.21.1     |Manifest: NOSIGNATURE
		[精致存储] refinedstorage-neoforge-2.0.9.jar          |Refined Storage               |refinedstorage                |2.0.9               |Manifest: NOSIGNATURE
		[资源下载机] Resourcify (1.21.1-neoforge)-1.8.5.jar    |Resourcify                    |resourcify                    |1.8.5               |Manifest: NOSIGNATURE
		[RF工具：基础] rftoolsbase-1.21-6.0.11.jar             |RFToolsBase                   |rftoolsbase                   |1.21-6.0.11         |Manifest: NOSIGNATURE
		sable-neoforge-1.21.1-2.0.3.jar                   |Sable                         |sable                         |2.0.3               |Manifest: NOSIGNATURE
		sable-companion-common-1.21.1-1.6.0.jar           |Sable Companion               |sablecompanion                |1.6.0               |Manifest: NOSIGNATURE
		[物品分拣] inventorysorter-1.21.1-24.0.24.jar         |Simple Inventory Sorter       |inventorysorter               |24.0.24             |Manifest: NOSIGNATURE
		net.caffeinemc.sodium-neoforge-0.8.12+mc1.21.1-mod|Sodium                        |sodium                        |0.8.12+mc1.21.1     |Manifest: NOSIGNATURE
		[钠 · 扩展] sodium-extra-neoforge-0.9.3+mc1.21.1.jar |Sodium Extra                  |sodium_extra                  |0.9.3+mc1.21.1      |Manifest: NOSIGNATURE
		[精妙背包] sophisticatedbackpacks-1.21.1-3.25.69.1979.|Sophisticated Backpacks       |sophisticatedbackpacks        |3.25.69             |Manifest: NOSIGNATURE
		[精妙核心] sophisticatedcore-1.21.1-1.4.72.2136.jar   |Sophisticated Core            |sophisticatedcore             |1.4.72              |Manifest: NOSIGNATURE
		spark-1.10.124-neoforge.jar                       |spark                         |spark                         |1.10.124            |Manifest: NOSIGNATURE
		[储物抽屉] StorageDrawers-neoforge-1.21.1-13.11.4.jar |Storage Drawers               |storagedrawers                |13.11.4             |Manifest: NOSIGNATURE
		[暮色森林] twilightforest-1.21.1-4.8.3345-universal.ja|The Twilight Forest           |twilightforest                |4.8.3345            |Manifest: NOSIGNATURE
		[钛] titanium-1.21-4.0.50.jar                      |Titanium                      |titanium                      |4.0.50              |Manifest: NOSIGNATURE
		TRansition-1.0.21-1.21.1-neoforge-SNAPSHOT.jar    |TRansition                    |transition                    |1.0.21              |Manifest: NOSIGNATURE
		TRender-1.0.15-1.21.1-neoforge-SNAPSHOT.jar       |TRender                       |trender                       |1.0.15              |Manifest: NOSIGNATURE
		veil-neoforge-1.21.1-4.1.4.jar                    |Veil                          |veil                          |4.1.4               |Manifest: NOSIGNATURE
		[飘扬披风] waveycapes-neoforge-1.10.2-mc1.21.1.jar    |WaveyCapes                    |waveycapes                    |1.10.2              |Manifest: NOSIGNATURE
		[传送石碑／指路石] waystones-neoforge-1.21.1-21.1.38.jar  |Waystones                     |waystones                     |21.1.38             |Manifest: NOSIGNATURE
		[正击何键] wip-neoforge-1.21-21.1.2.jar               |WhatImPressing                |wip                           |21.1.2              |Manifest: NOSIGNATURE
		[地牢浮现之时] DungeonsArise-1.21.1-2.1.68-release.jar  |When Dungeons Arise           |dungeons_arise                |2.1.68              |Manifest: NOSIGNATURE
		[Xaero的小地图] xaerominimap-neoforge-1.21.1-26.4.2.ja|Xaero's Minimap               |xaerominimap                  |26.4.2              |Manifest: NOSIGNATURE
		[Xaero的世界地图] xaeroworldmap-neoforge-1.21.1-1.44.2.|Xaero's World Map             |xaeroworldmap                 |1.44.2              |Manifest: NOSIGNATURE
		xaerolib-neoforge-1.21.1-1.7.1.jar                |XaeroLib                      |xaerolib                      |1.7.1               |Manifest: NOSIGNATURE
		xnet-1.21-7.0.7.jar                               |XNet                          |xnet                          |1.21-7.0.7          |Manifest: NOSIGNATURE
	Crash Report UUID: df0daf7c-3f69-4c7b-9cca-f0a94afacdb9
	FML: 4.0.44
	NeoForge: 21.1.249
	Flywheel Backend: flywheel:indirect
#@!@# Game crashed! Crash report saved to: #@!@# C:\Users\AI-777hi\AppData\Roaming\.minecraft\versions\1.21.1-NeoForge_21.1.249\crash-reports\crash-2026-08-31_07.19.40-server.txt