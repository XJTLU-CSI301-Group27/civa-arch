# 第二轮 UI 文件变更（2026-11-25）

相对于本轮开始的本地工程，共 23 个源码/资源文件；不是相对于远端 main 的差异。
原始源码有备份，完整可读差异见 [source-changes.diff](verification/cat-refresh/source-changes.diff)。

| 文件 | 动作 | 当前 SHA-256 |
| --- | --- | --- |
| `app/src/main/java/edu/xjtlu/civa/AppIntroView.java` | 修改 | `e8c5cbab0aa1cbdcb22e7419900966113d06a2181e675b85520cc6d279b8ea37` |
| `app/src/main/java/edu/xjtlu/civa/MainActivity.java` | 修改 | `4ded50c25c0a911577430efbbd40f55ae54f10d8b856bb5be99529d93b0f7200` |
| `app/src/main/res/values/app_bakery_strings.xml` | 修改 | `c9a87195d17d68d2ff9751bfd7d0e055f970265efc634357495a82435593bdae` |
| `core-ui/src/main/java/edu/xjtlu/civa/core/ui/BakeryWelcomeLayout.java` | 修改 | `fe756cfc3d92db6f75f8c42f231656e205a7b08e122698b2a9b01819d69e6cb3` |
| `core-ui/src/main/res/drawable-nodpi/civa_cat_bread.png` | 新增 | `ae0d81bce1890b349d67c1c2cd63f7fd3c01351e56e86794a5cd86f02c518852` |
| `core-ui/src/main/res/drawable-nodpi/civa_cat_egg.png` | 新增 | `2d48cc84cb2b1cef93299eef9fe0874f123f3fe36a020ab30dc9fb906b267226` |
| `core-ui/src/main/res/drawable-nodpi/civa_cat_family.png` | 新增 | `e8264d701613e6807a5c2a6d4d4410d27d380ef8bdf95a234f608a333646b936` |
| `core-ui/src/main/res/drawable-nodpi/civa_cat_long_hat.png` | 新增 | `1e1380bdc72953b08cd1b5df96d31434e71be3d72228cea057494c66b293aea5` |
| `core-ui/src/main/res/drawable-nodpi/civa_cat_pan.png` | 新增 | `fcdfd29997099dc45238cd7bcbe84fe35491ac20ac78a50e83050bf33d8cf85d` |
| `core-ui/src/main/res/values/civa_colors.xml` | 修改 | `cec78952881650ce24c517a7a2230a776a68aef12fa1d0e6ab593c6b5190543c` |
| `feature-ac/src/main/res/layout/ac_fragment_catalog.xml` | 修改 | `a0c01a7400b36119d8d4580aef7022e3a62b674d4b8fb6bb1d3906fa205123cb` |
| `feature-bd/src/main/java/edu/xjtlu/civa/feature/bd/BdHomeLayout.java` | 修改 | `f069a5d0f43574e0fcd4276c29aa386e50b22b57a01df5bb1d0b009f1e46acd2` |
| `feature-bd/src/main/res/layout/bd_fragment_home.xml` | 修改 | `1685f4fb0ca993eae14a526358ebc4d14099188ad02f4ef75b0c8c128b8a0474` |
| `feature-bd/src/main/res/layout-w600dp/bd_fragment_home.xml` | 修改 | `f899c1ebb4da4b3794b44a66c7921a5a13650ffdfee81f93696b3e2129ceb6b3` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/ProfileEditDialog.java` | 修改 | `c549260f22011b698aa4273cd22fd628f40ab9677ae329ce214a7bb6ba138c83` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/ProfileFragment.java` | 修改 | `6019d9ac7dc2676b43edeca4000b6421e4f875b34cda6afb4b7d7acfc3b02eb2` |
| `feature-ef/src/main/res/drawable/ef_ic_language_globe.xml` | 新增 | `6520c1a89ac94fe591cd21c8e6d35153d5306db093bd8c4260516d2fd1e7f4ef` |
| `feature-ef/src/main/res/drawable/ef_language_button.xml` | 新增 | `6899bf8ea66181a1c0effb36a6e5989f9448f3de570c0aeca875d43360b728da` |
| `feature-ef/src/main/res/layout/ef_fragment_login.xml` | 修改 | `589c655bf5678f8f232e0c7530561ff537bb8c670496630768aa73034a183508` |
| `feature-ef/src/main/res/layout/ef_fragment_profile.xml` | 修改 | `fc2e00e5d2d26b18d632ccbde2d0f1bb864a19a7373a9f3da0cb47d15c5b72bf` |
| `feature-ef/src/main/res/values/ef_profile_ids.xml` | 修改 | `377bc07e135a5160bda1a5b780ec8056331a569ba17113185122eb4befc16b4d` |
| `feature-ef/src/main/res/values/ef_strings.xml` | 修改 | `ecb91ef726bfc631119a4acccf9a449e9a0a44410c88c7f2ebdb701d1bb849cd` |
| `app/src/main/res/raw/app_cat_intro.webm` | 新增 | `0ef500ad1f130c0737107ee02c22a4c50ffb01cec64d3b9bf558a974d406dcfa` |

文档维护：新增 EF_CAT_REFRESH_HANDOFF、此清单及 CAT_REFRESH_VERIFICATION；更新
EF_BAKERY_UI_HANDOFF 的历史提示及 UI_RULES、TEST_PLAN、DATA_CONTRACT、HANDOFF。
素材、生成提示词、视频编码参数和输入/输出哈希已记录在 CAT_REFRESH_ASSETS.json。
无生成 API、服务端、Gradle 依赖、用户数据格式或其他工程副本变动。
最初新增的 H.264 试验文件已替换为最终 WebM；原始桌面 MP4 未改动。
