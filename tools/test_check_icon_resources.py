#!/usr/bin/env python3
"""图标静态门禁的临时夹具回归；不操作项目正式资源。"""
import json
from pathlib import Path
import tempfile
import unittest
import check_icon_resources as gate


class IconResourcesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.registry = self.root / gate.REGISTRY
        self.registry.parent.mkdir(parents=True)
        self.resources = self.root / "src/main/resources/assets/living_item"
        self.declare()

    def declare(self, body=None):
        self.registry.write_text(body or '''class Registry {
            void registerAll() {
                register(LivingIconSpec.builder(Items.STONE)
                    .addLocalVariant("base", "item/example", stack -> true).build());
            }
        }''', encoding="utf-8")

    def model(self, name="item/example", value=None):
        path = self.resources / "models" / (name + ".json")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value if value is not None else {
            "parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:block/stone"}
        }), encoding="utf-8")
        return path

    def fails(self, text):
        with self.assertRaisesRegex(gate.InvalidDeclaration, text):
            gate.check(self.root)

    def test_new_declaration_missing_model_fails(self):
        self.fails("缺少声明模型")

    def test_malformed_json_fails(self):
        self.model().write_text("{", encoding="utf-8")
        self.fails("JSON 解析失败")

    def test_missing_owned_parent_fails(self):
        self.model(value={"parent": "living_item:item/not_created"})
        self.fails("缺少声明模型/parent")

    def test_parent_cycle_fails(self):
        self.model(value={"parent": "living_item:item/other"})
        self.model("item/other", {"parent": "living_item:item/example"})
        self.fails("parent 环")

    def test_missing_owned_texture_fails(self):
        self.model(value={"textures": {"layer0": "living_item:item/not_created"}})
        self.fails("缺少本地纹理")

    def test_external_parents_textures_and_texture_variables_are_not_local_files(self):
        self.model(value={"parent": "builtin/entity", "textures": {
            "particle": "other_mod:block/external", "side": "#particle"
        }})
        self.assertEqual((1, 1, 0), gate.check(self.root))

    def test_unknown_dynamic_declaration_fails_instead_of_being_skipped(self):
        self.declare('''class Registry { void registerAll() {
            register(LivingIconSpec.builder(Items.STONE)
                .addLocalVariant("base", dynamicModel(), stack -> true).build());
        }}''')
        self.fails("字面量")

    def test_owned_absolute_and_parent_paths_fail(self):
        for path in ("/outside", "item/../outside", "item/./outside", "item//outside"):
            with self.subTest(path=path):
                self.declare('''class Registry { void registerAll() {
                    register(LivingIconSpec.builder(Items.STONE)
                        .addLocalVariant("base", "''' + path + '''", s -> true).build());
                }}''')
                self.fails("非法本地资源路径")

    def test_owned_builtin_prefix_is_not_external(self):
        self.model(value={"parent": "living_item:builtin/entity"})
        self.fails("缺少声明模型/parent")

    def test_unknown_builder_method_fails(self):
        self.model()
        self.declare(self.registry.read_text(encoding="utf-8").replace(
            '.build()', '.newAssetMode("dynamic").build()'))
        self.fails("未知图标 builder 调用")

    def test_unknown_registration_helper_fails(self):
        self.model()
        self.declare(self.registry.read_text(encoding="utf-8") + '\nregisterNewIcon(Items.STONE, "item/new");')
        self.fails("未知图标注册调用")

    def test_rotating_metadata_skipped_without_dummy_model(self):
        self.model()
        self.declare(self.registry.read_text(encoding="utf-8") + '''
            register(LivingIconSpec.builder(Items.STONE)
                .addLocalVariant("base", "item/absent_wheel", stack -> true).rotating().build());
        ''')
        self.assertEqual((1, 1, 1), gate.check(self.root))

    def test_explicit_namespace_preserved(self):
        self.declare('''class Registry { void registerAll() {
            register(LivingIconSpec.builder(Items.STONE)
                .addVariant("base", ResourceLocation.fromNamespaceAndPath("living_item", "item/example"),
                    stack -> true).build());
            register(LivingIconSpec.builder(Items.STONE)
                .addVariant("base", ResourceLocation.parse("foreign:item/example"),
                    stack -> true).build());
        }}''')
        self.model()
        self.assertEqual((2, 1, 0), gate.check(self.root))

    def test_helper_arguments_are_checked(self):
        self.declare('''class Registry {
            void registerAll() { registerCopperBlock(Items.STONE, "item/example"); }
            void registerCopperBlock(Item item, String texture) {
                register(LivingIconSpec.builder(item)
                    .addLocalVariant("base", texture, stack -> true).build());
            }
        }''')
        self.fails("缺少声明模型")
        self.model()
        self.assertEqual((1, 1, 0), gate.check(self.root))

    def test_owned_texture_exists_and_bad_shapes_fail(self):
        texture = self.resources / "textures/item/own.png"
        texture.parent.mkdir(parents=True)
        texture.write_bytes(b"fixture")
        path = self.model(value={"textures": {"layer0": "living_item:item/own"}})
        self.assertEqual((1, 1, 0), gate.check(self.root))
        path.write_text("[]", encoding="utf-8")
        self.fails("必须是对象")


if __name__ == "__main__":
    unittest.main()
