# 本轮文件变更清单（2026-11-25）

以下为相对于本轮开始时本地源码的清单，不代表相对于远端 main 的差异。
保留原接口和闪退修复；新资源、实现和测试共 30 个文件。完整文字差异见[验证补丁](verification/bakery-onboarding/ui-source-changes.diff)。

| 文件 | 动作 | 当前 SHA-256 |
| --- | --- | --- |
| `contracts/public-surface.draft.json` | 修改 | `6768bf999da8082fcb0f120945254a2b341c286c2734e8c24cacf9b50cccc19d` |
| `feature-ef/build.gradle` | 修改 | `a205a3d0eaa474a7e39432c5b1e679fc404272b1f8fc34f5e65a9b4e029d7b08` |
| `app/src/main/java/edu/xjtlu/civa/AppIntroView.java` | 新增 | `aff5f179f69d516fe112641b671593f3ee350fe4a7b321c0acd29d24bb19796f` |
| `app/src/main/java/edu/xjtlu/civa/MainActivity.java` | 修改 | `bd8c1cae6651e7823bcfb0b5236087e4f7498ec97c4ec947691c0086cf4627d9` |
| `app/src/main/res/values/app_bakery_strings.xml` | 新增 | `e46583d27a5b35dc46b1ce874793dfdbc36e18b57a628664a2c31875c1b58d9a` |
| `app/src/main/res/values/app_intro_ids.xml` | 新增 | `741732340a9ec19fd512e192676947dec337165c4eb48a94615a432fe06e3616` |
| `core-ui/src/main/java/edu/xjtlu/civa/core/ui/BakeryCatStage.java` | 修改 | `fd0b78804a8b97f985f852417025d5e8cfb594ae09d586c07ef638d4294f83ee` |
| `core-ui/src/main/java/edu/xjtlu/civa/core/ui/BakeryWelcomeLayout.java` | 新增 | `8b7a6aca5916125257eaa07fdff93df81f74b96dddc20846b20f28a1a030a078` |
| `core-ui/src/main/java/edu/xjtlu/civa/core/ui/ChefCatView.java` | 新增 | `11660803a560b97072c3dd5be3a6b419e6259d32b76de37db9ddfe3917dd603a` |
| `core-ui/src/main/java/edu/xjtlu/civa/core/ui/MeowSound.java` | 新增 | `ea27762c9bd5cae76d5d6e4eff9ee85c3ed4e427ec9fbe16536cf8cec582b7e1` |
| `core-ui/src/main/res/raw/civa_meow.ogg` | 新增 | `ae35c578eb7e7bc33d1dc7b2532cf4cdc8dac27f8cf28c6d574fb3027be83d5f` |
| `core-ui/src/main/res/values/civa_colors.xml` | 修改 | `e9ec0ccfcb816704eb20dd1c0852cc38e4e1f70ff3223067b5362c01fe52db65` |
| `core-ui/src/main/res/values/civa_styles.xml` | 修改 | `2ed204e326ad1874fbcde8e5b5ccb13d59615625f2ebb21bae543a39e361f90c` |
| `core-ui/src/main/res/values/civa_ui_ids.xml` | 修改 | `ffb03390bfa41a6bbffbb9c5d90701f523dbd851a4eac6aa91497ea897ebbabc` |
| `feature-ac/src/main/java/edu/xjtlu/civa/feature/ac/catalog/RecipeCatalogDetailFragment.java` | 修改 | `a5608f281d542ca4a07a8b084c0c0e10df06b8c4e35e4538f74a198b61fb5330` |
| `feature-ac/src/main/res/layout/ac_fragment_catalog_detail.xml` | 修改 | `42adab0c292b3472913a3de1c108aba7a5ba71ea000bcb8bc283d5c37ff4d7a9` |
| `feature-ac/src/main/res/values/ac_strings.xml` | 修改 | `6a3c8c1745299111733feebe942eb9c6cd67687870376ae0f5e4e985afafee24` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/HouseholdFragment.java` | 修改 | `23a25b0c145b63922d28d7c47b29d6d6f89f954b9bdb8b3169e9b921b16fc42e` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/LocalProfileStore.java` | 新增 | `4f620378e2600381cf7ce92567083173b4c0536710ae9218c17c61ed3f6186a5` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/LoginFragment.java` | 修改 | `31a65bbd355ecce74438cf0a46c933f84b83ed5db56b1710f927e95b9c74dc36` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/ProfileEditDialog.java` | 新增 | `0e7fce30b7d569f809b95dd9f70a213d6e50a20d1bbb8ca16d8d240773cb9b9c` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/ProfileFragment.java` | 修改 | `021a9d90dab6c9a1b54e346702f263c0d0e31bd0c06a939e5f7c1c58a73b44e6` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/ProfileIdentity.java` | 新增 | `a2e45c9d8ee7d681f54a6d1c808bb0d5589c71ca69628b9e3a2b2d1191e3ac6e` |
| `feature-ef/src/main/res/drawable/ef_ic_edit.xml` | 新增 | `e42a47675a8219ceba6f466884b2930239a8aa632ae2e3349c9090b90a5d2aa8` |
| `feature-ef/src/main/res/layout/ef_fragment_household.xml` | 修改 | `3d07cb076ba26460f3ad71a6a067764a4e736aa1493e202c95b3d8f963c814df` |
| `feature-ef/src/main/res/layout/ef_fragment_login.xml` | 修改 | `6a693259194b6d19feb862262cc61b1ceefb73c7ee1442deb965a565928e42df` |
| `feature-ef/src/main/res/layout/ef_fragment_profile.xml` | 修改 | `b9483bff6add214712b919f1223c4c3b0967cdc9900051111d226d2b9867ede0` |
| `feature-ef/src/main/res/values/ef_profile_ids.xml` | 新增 | `6a9dd0a28e0b65b294239e0a2cad51d8cea56eee265d1fa2a6d6c5fb4b01f78f` |
| `feature-ef/src/main/res/values/ef_strings.xml` | 修改 | `8a1bf34f1ad71b55a4ba79c2ac5e3db6d6219c144acb300f811dc3da9515b72a` |
| `feature-ef/src/test/java/edu/xjtlu/civa/feature/ef/ProfileIdentityTest.java` | 新增 | `e64e739ed39dbe84cd82cc9ddf01fa0d3c65d44b44ab9197855817e1d3d14e09` |

## 文档与验证补充

- 新增本交接、变更清单、验证记录三份文档。
- 更新 UI_RULES、DATA_CONTRACT、TEST_PLAN、HANDOFF 的本轮章节，原有记录保留。
- docs/verification/screenshots/bakery-onboarding/ 为 16 张选定截图（含正式包最终截图）；docs/verification/bakery-onboarding/ 为构建/契约日志与补丁。
- 测试 applicationId 覆盖、临时账号凭据、自动化脚本、测试应用私有数据未放入工程。
- server、生成 API、.env、local.properties、其余项目副本未因本轮 UI 任务修改。
