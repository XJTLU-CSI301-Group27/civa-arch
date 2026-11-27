# Phase D1-Fix 鍙樻洿璁板綍锛氬弻绫诲瀷瀵煎叆 E2E銆佸鍏ユ仮澶嶄笌涓汉蹇収鏍告煡

鏃ユ湡锛?026-11-27锛圓sia/Shanghai锛?鍩虹嚎锛歚b3aae70af69ecac9083ec239fd42068b304bf21c`
鑼冨洿锛氫粎 D1-Fix锛涙湭淇敼鏈嶅姟绔€佹暟鎹簱銆丗lyway銆丱penAPI銆佸簱瀛樼畻娉曘€?
## 1. 缁撴灉鎽樿

- 鐪熷疄 UI 娣峰悎瀵煎叆閫氳繃锛?  - 楦¤泲 3 COUNT銆乥eef 200 G 榛樿 TRACKED锛?  - 椋熺洂 閫傞噺銆侀叡娌?2 鍕洪€夋嫨 RECIPE_ONLY锛?  - DB 鍚屾椂淇濆瓨 2 鏉?tracked + 2 鏉?recipe-only銆?- 鐪熷疄 UI 浠?RECIPE_ONLY 瀵煎叆姝ゅ墠宸查€氳繃锛涙湰杞啀娆℃牳瀵广€?- 鐪熷疄 UI 瀵煎叆 UNKNOWN 鈫?鍋滄鍚庣 鈫?`am force-stop` 鐪熸杩涚▼姝讳骸 鈫?閲嶅紑 鈫?鎭㈠ source=IMPORT pending 鈫?鎵嬪姩閲嶈瘯 鈫?鍙垱寤轰竴鏉★紝pending 娓呯悊銆?- B1 snapshot 鏍告煡锛歚HouseholdRecipeService.snapshot` 纭疄鎶?RECIPE_ONLY 鍐欏叆涓汉鑿滆氨鑷敱鏂囨湰 `ingredients`锛宍HouseholdRecipeOnlyHttpIntegrationTest` 瑕嗙洊锛汥1 鎶ュ憡涓€滀釜浜鸿彍璋卞畬鍏ㄦ棤娉曚繚瀛?RECIPE_ONLY 璇存槑鈥濈殑琛ㄨ堪涓嶅噯纭紝宸茬籂姝ｃ€?- 鏈€灏忎慨澶嶏細瀵煎叆 pending 鐨勬仮澶?妫€娴嬩笉鍐嶅彧鍦ㄥ搴彍璋辫鎯呰Е鍙戯紱鍏叡/涓汉璇︽儏鍏ュ彛涔熻兘鍙戠幇 IMPORT pending 骞惰繘鍏ュ師璇锋眰閲嶈瘯銆?
## 2. 淇敼鏂囦欢

**淇敼**
- `RecipeCatalogDetailFragment.java`
  - 鎵€鏈夎鎯咃紙鍏叡/涓汉/瀹跺涵锛夊姞杞藉悗妫€鏌?source=IMPORT pending锛?  - 瀵煎叆鍏ュ彛閬囧埌 IMPORT pending 鏃剁洿鎺ユ樉绀衡€滈噸璇曞師瀵煎叆鈥濆璇濇锛岃€屼笉鏄彧缁欐櫘閫氭彁绀猴紱
  - 鎵嬪伐 pending 浠嶆樉绀衡€滃厛澶勭悊寰呯‘璁ゅ垱寤衡€濈殑闃诲鎻愮ず銆?
**鏂板**
- `PHASE_D1_FIX_CHANGE_RECORD.md`

鏈柊澧?Contract/鏁版嵁搴撹縼绉伙紝鏈敼鏈嶅姟绔€?
## 3. 浠?TRACKED 瀵煎叆

**鏈畬鎴愬崟鐙殑鈥滀粎楦¤泲+鐗涜倝鈥濈湡瀹?UI 瀵煎叆銆?*

宸查€氳繃娣峰悎瀵煎叆楠岃瘉 A/B 榛樿 TRACKED锛?
- 楦¤泲 3 COUNT 鈫?`recipe_ingredients`
- beef 200 G 鈫?`recipe_ingredients`
- `trackStock=true`
- 鍗曚綅鏉ヨ嚜瀛楀吀
- recipe-only 涓嶅寘鍚繖涓や釜 A/B 琛?
`ImportRequestTest` 瑕嗙洊浠?TRACKED 璇锋眰锛涗絾涓ユ牸鐨勨€滀粎 TRACKED 鏉ユ簮 UI鈥濅粛鏈崟鐙墽琛屻€?
## 4. 娣峰悎瀵煎叆瀹炴祴

鏉ユ簮锛氫复鏃朵釜浜鸿彍璋?`D1FixSource`锛堟祴璇曞悗鍒犻櫎锛夈€?
UI锛?- 楦¤泲 3 COUNT锛氶粯璁?TRACKED
- beef 200 G锛氶粯璁?TRACKED
- 椋熺洂 閫傞噺 / 澶囨敞鈥滆皟鍛斥€濓細榛樿鏈€夋嫨锛屾敼涓?RECIPE_ONLY
- 閰辨补 2 鍕猴細榛樿鏈€夋嫨锛屾敼涓?RECIPE_ONLY

淇濆瓨缁撴灉锛?
- 瀹跺涵鑿滆氨 `9e3c769a-2b15-4cef-8e9e-7e9fc572e6f0`
- `recipe_ingredients=2`锛?  - `beef 200.000 G trackStock=true`
  - `楦¤泲 3.000 COUNT trackStock=true`
- `recipe_only_ingredients=2`锛?  - `椋熺洂 / 閫傞噺 / note=璋冨懗`
  - `閰辨补 / 2 鍕?/ note=null`
- 璇︽儏鏄剧ず锛?  - `楦¤泲 3 涓猔
  - `beef 200 鍏媊
  - 鈥滀粎璇存槑杈呮枡鈥濓細`椋熺洂 閫傞噺 (璋冨懗)`銆乣閰辨补 2 鍕篳
- 閲嶆柊鎵撳紑缂栬緫鍣細楦¤泲 3銆乥eef 200銆侀鐩?閰辨补浠嶅湪銆?- 娴嬭瘯鍚庡凡鍒犻櫎瀹跺涵鑿滆氨鍜屼釜浜?source/snapshots銆?
**鏈疄娴?*锛?- A/B 涓诲姩鏀逛负 RECIPE_ONLY銆?- C 鏀逛负 TRACKED銆?- `1.5 kg鈫?500 G` / `1 L鈫?000 ML` UI 瀵煎叆锛坧arser 鍗曟祴宸茶鐩栵級銆?- `2 鍕篳 涓嶈鎹㈢畻锛氭贩鍚堝鍏ュ凡瀹為檯鐪嬪埌 `2 鍕篳 鍘熸牱淇濆瓨銆?- 鏃犲崟浣?鏃犵敤閲?RECIPE_ONLY锛氭鍓嶄釜浜?`aaaa` 瀵煎叆瀹為檯楠岃瘉 amountText=null銆?
## 5. 瀵煎叆 UNKNOWN 杩涚▼姝讳骸鎭㈠

瀹為檯娴佺▼锛?
1. 浠庝釜浜鸿彍璋?`D1FixSource` 璇︽儏鍙戣捣瀵煎叆銆?2. 鏍稿 2 TRACKED + 2 RECIPE_ONLY銆?3. 鍋滄 B1 鍚庣銆?4. 鐐瑰嚮瀵煎叆銆?5. 鍑虹幇鈥滃鍏ョ粨鏋滀笉纭畾鈥濓紝浠呪€滈噸璇曞師瀵煎叆鈥濄€?6. 鏈湴 pending锛?   - key `23dce605-80e3-416d-a21c-deee2218ee1a`
   - operation `CREATE_HOUSEHOLD_RECIPE`
   - `needsReview=1`
   - `source=IMPORT`
   - payload 2 tracked + 2 recipe-only銆?7. `am force-stop edu.xjtlu.civa`锛堢湡姝ｈ繘绋嬫浜★紝涓嶆槸鏃嬭浆锛夈€?8. 閲嶅紑 App銆佺櫥褰曘€?9. 鎵撳紑涓汉鑿滆氨璇︽儏锛?   - `resumeImportPending` 鑷姩鍙戠幇 pending锛?   - 鏄剧ず鈥滈噸璇曞師瀵煎叆鈥濓紱
   - 涓嶈嚜鍔ㄩ噸鍙戙€?10. 閲嶅惎鍚庣銆?11. 鐐瑰嚮閲嶈瘯銆?12. pending key 浠嶄负 `23dce605-...`锛宲ayload/fingerprint 鏈彉銆?13. DB 鍙柊澧炰竴鏉″搴彍璋憋紙閲嶈瘯缁撴灉 `e02ca526-...`锛夛紱鍘?UNKNOWN 鏈骇鐢熺浜屾潯銆?14. 鏈湴 pending 璁℃暟鍙樹负 0銆?
**鏈疄娴?*锛?- 鍏朵粬璐﹀彿/瀹跺涵闅旂鐨勭湡瀹炶澶囧垏鎹€?- 鍚屾椂瀛樺湪鎵嬪伐 pending 鐨勮澶囧啿绐併€?- 鎹熷潖 pending 鐪熸満娉ㄥ叆銆?
浠ｇ爜浠嶄娇鐢?D0锛?- `CreateAttemptStore`锛?- `PendingWriteStore`锛?- 鍘?key/payload锛?- 璐﹀彿/home 鏍￠獙锛?- 涓嶈嚜鍔ㄩ噸鏀俱€?
## 6. 涓汉鑿滆氨蹇収鏍告煡

### B1 浜嬪疄

`HouseholdRecipeService.snapshot` 瀛樺湪锛?
- 瀵?TRACKED锛氬啓涓汉鑿滆氨 `ingredients` 鐨?name/quantity/unit锛?- 瀵?RECIPE_ONLY锛氬啓 name銆乹uantity=amountText銆乽nit=null銆乶ote銆乻ourceText=amountText锛?- 鍥犳涓汉鑿滆氨鑷敱鏂囨湰缁撴瀯鍙互琛ㄨ揪鈥滈鐩愶細閫傞噺鈥濄€?
鐜版湁娴嬭瘯锛?- `HouseholdRecipeOnlyHttpIntegrationTest.recipeOnlyIngredientsRoundTripAndSnapshot`
- 鏂█ snapshot 鍖呭惈椋熺洂/閫傞噺銆侀叡娌?2 鍕恒€侀鑿溿€?
鏈疆瀹為檯瀹跺涵娣峰悎瀵煎叆鍚庯紝涓汉 snapshot 瀹為檯涓猴細

```
楦¤泲 COUNT 3
beef G 200
椋熺洂 quantity=閫傞噺 sourceText=閫傞噺 note=璋冨懗
閰辨补 quantity=2 鍕?sourceText=2 鍕?```

缁撹锛?
- B1 snapshot 宸插畬鏁存敮鎸?RECIPE_ONLY 璇存槑锛堜娇鐢ㄨ嚜鐢辨枃鏈粨鏋勶級銆?- D1 鎶ュ憡涓殑鈥滀釜浜鸿彍璋辨病鏈?recipeOnlyIngredients 瀛楁锛屾墍浠ヨ法妯″瀷蹇収涓嶆敮鎸?RECIPE_ONLY鈥濇槸鎺緸閿欒锛涙湰杞籂姝ｃ€?- 涓嶉渶瑕佷负涓汉鑿滆氨鏂板 recipeOnlyIngredients Contract 瀛楁鎴栨暟鎹簱杩佺Щ銆?
## 7. 绌哄簱瀛樺鍏?
**鏈墽琛岀湡瀹炵┖搴撳瓨娴嬭瘯銆?*

鏈娇鐢ㄧ┖搴撳瓨瀹跺涵瀹為檯瀵煎叆 TRACKED-only/娣峰悎骞舵煡鐪?match 缂烘枡銆佽鍒掓坊鍔犮€佹棤娴佹按銆備唬鐮佷笉璇诲彇搴撳瓨鎵规锛屼絾鎸夎姹備笉鑳戒互浠ｇ爜瀹℃煡鏇夸唬瀹炴祴锛涘垪涓烘湭鎵ц銆?
## 8. 鎭㈠鍏ュ彛浼樺寲

鏈€灏忎慨澶嶏細

- `resumeImportPending()` 鐜板湪鍦ㄥ叕鍏?涓汉/瀹跺涵璇︽儏鍔犺浇鍚庨兘浼氭鏌ュ綋鍓?user/home 鐨?`source=IMPORT` pending銆?- 鎵撳紑鍘熶釜浜?鍏叡鑿滆氨璇︽儏鍗冲彲鐪嬪埌鈥滈噸璇曞師瀵煎叆鈥濓紝涓嶅繀鍏堢寽鍘诲搴彍璋辫鎯呫€?- 瀵煎叆鍏ュ彛閬囧埌 pending锛?  - 鑻?source=IMPORT 鈫?鐩存帴鏄剧ず鍘熻姹傞噸璇曞璇濇锛?  - 鑻?source=MANUAL 鈫?鏄剧ず鈥滄湁寰呯‘璁ゅ垱寤衡€濋樆濉炴彁绀猴紝蹇呴』鍏堝鐞嗘墜宸?pending銆?
鏈紑鍙戜换鍔′腑蹇?鍚庡彴鏈嶅姟銆?
## 9. 娴嬭瘯涓庢瀯寤?
| 鍛戒护 | 缁撴灉 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **229 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL锛坈ontracts consistent锛?|
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| `git diff --check` | 鏃犵┖鐧介敊璇?|

## 10. 鏈墽琛屾祴璇?
- 鐙珛浠?TRACKED 鏉ユ簮 UI 瀵煎叆銆?- A/B鈫扲ECIPE_ONLY UI銆?- C鈫扵RACKED UI銆?- 1.5kg/1L UI 鎹㈢畻銆?- 绌哄簱瀛樺搴疄闄呭鍏ャ€乵atch 缂烘枡銆佽鍒掓坊鍔犮€佹棤娴佹按銆?- 璐﹀彿/瀹跺涵鍒囨崲銆佹墜宸?pending 鍐茬獊銆佹崯鍧?pending 鐪熸満銆?- 360dp銆佸瓧浣撴斁澶с€佹繁鑹层€佽嫳鏂囥€佹í灞忓鍏ユ牳瀵广€?- 瀵煎叆鍚庡畬鏁寸紪杈戝櫒/璇︽儏閲嶆柊璇诲彇锛堟贩鍚堝凡閮ㄥ垎楠岃瘉锛夈€?
## 11. 鍓╀綑椋庨櫓

- 鐙珛浠?TRACKED UI 涓?C鈫扵RACKED/A/B鈫扲ECIPE_ONLY 鏈疄娴嬨€?- 绌哄簱瀛樻湭瀹炴祴銆?- 瀵煎叆 pending 妫€娴嬪湪浠讳綍璇︽儏椤甸兘鍙兘寮圭獥锛涘鏃犲叧娴忚鍙兘鏈夋墦鎵帮紝浣嗕笉浼氳嚜鍔ㄩ噸鍙戙€?- 涓汉 snapshot 宸茬敱 B1 鏀寔锛汥1 鏂囨。宸茬籂姝ｏ紝涓嶉渶瑕佹柊瀛楁銆?- 鎵嬪伐 pending 涓庡鍏?pending 鍏辩敤 operation锛涢€氳繃 source/闃诲鎻愮ず鍖哄垎锛屼絾娌℃湁鐙珛娓呯悊鍏ュ彛銆?- 鏈敼搴撳瓨绠楁硶銆?
## 12. Phase D 鏄惁婊¤冻鍏抽棴鏉′欢

**涓昏 P0 宸查獙璇侊紝寤鸿鏉′欢鍏抽棴锛涘畬鏁?D1 楠屾敹鐭╅樀浠嶆湭鍏ㄩ儴閫氳繃銆?*

宸查€氳繃锛?
- 娣峰悎鍙岀被鍨嬬湡瀹?UI 瀵煎叆锛?- 浠?RECIPE_ONLY 鐪熷疄瀵煎叆锛?- 瀵煎叆 UNKNOWN 杩涚▼姝讳骸鎭㈠涓庡師 key/payload 閲嶈瘯锛?- B1 涓汉蹇収鏍告煡锛?- 璇︽儏/缂栬緫鍣ㄥ弻绫诲瀷灞曠ず銆?
鏈畬鎴愶細

- 鐙珛浠?TRACKED UI锛?- A/B鈫扲ECIPE_ONLY銆丆鈫扵RACKED UI锛?- 绌哄簱瀛橈紱
- 360dp/瀛椾綋/娣辫壊/鑻辨枃锛?- 璐﹀彿/瀹跺涵/鎹熷潖 pending 鐪熸満銆?
## 13. Phase C 鍓嶇疆鏉′欢

1. 瀹屾垚鐙珛浠?TRACKED 涓?C鈫扵RACKED/A/B鈫扲ECIPE_ONLY UI銆?2. 瀹屾垚绌哄簱瀛樺鍏?璁″垝/鏃犳祦姘淬€?3. 瀹屾垚 360dp/瀛椾綋/娣辫壊/鑻辨枃銆?4. 璐﹀彿/瀹跺涵/鎹熷潖 pending 鐪熸満鎭㈠銆?5. 鑻ヤ釜浜鸿彍璋变粛闇€鐙珛 recipeOnly 瀛楁锛屽厛璧?Contract 鍙樻洿瀹℃壒锛涘綋鍓?B1 鑷敱鏂囨湰 snapshot 宸插彲琛ㄨ揪璇存槑銆?6. Phase C 鑷畾涔夐鏉愮嫭绔嬭璁★紝涓嶅緱澶嶇敤 RECIPE_ONLY 浼搴撳瓨椋熸潗銆?
## 14. 鏁版嵁娓呯悊

- 鍒犻櫎鎵€鏈?D1FixSource 瀹跺涵鑿滆氨锛?锛変笌涓汉 source/snapshots锛?锛夈€?- 鍒犻櫎 D1TrackedSource 涓汉 source锛堟湭瀵煎叆锛夈€?- 鏈湴 pending_writes 璁℃暟 0銆?- OurHome 鎭㈠鍘?6 閬撹彍璋憋紱inventory movements 淇濇寔 8銆?- 鏈骇鐢熷仛楗墸鍑?椋熻銆?- 娴嬭瘯璁惧 Pinyin IME 宸叉仮澶嶏紝ADBKeyboard 宸插嵏杞姐€?
## 15. D1-Fix 绗簩杞拷鍔犲疄娴嬶紙HEAD 32d53f8锛?
璇锋眰鍩虹嚎鍐欑殑鏄?b3aae70锛涘疄闄呬粨搴撳凡鍦?D1-Fix 鎻愪氦 32d53f8 涓婏紝鏈疆鍦ㄦ鍩虹涓婄户缁獙璇侊紝娌℃湁閲嶅淇敼宸叉彁浜ら€昏緫銆?
### 浠?TRACKED 鐪熷疄 UI锛氶€氳繃

鏉ユ簮涓汉鑿滆氨 `D1TrackedOnlySource`锛?- 楦¤泲 3 COUNT
- beef 200 G

UI 浠庝釜浜鸿鎯?鈫?鍔犲叆瀹跺涵 鈫?ImportReviewDialog锛岄粯璁?TRACKED锛屽～鍐欎唤鏁?2銆佹椂闂?20锛屽鍏ャ€?
DB锛?- family recipe `196a339d-ad13-47bd-977b-c899c2c1dc4d`
- `recipe_ingredients=2`锛?  - beef 200.000 G trackStock=true
  - 楦¤泲 3.000 COUNT trackStock=true
- `recipe_only_ingredients=[]`
- 璇︽儏瀹為檯鏄剧ず `楦¤泲 3 涓猔銆乣beef 200 鍏媊銆?
### A/B鈫扲ECIPE_ONLY 鐪熷疄 UI锛氶€氳繃

鍚屼竴 `D1TrackedOnlySource` 鍐嶆瀵煎叆锛?- 楦¤泲銆乥eef 涓よ mode 鏀逛负 RECIPE_ONLY銆?- DB family recipe `da89f9c2-51d3-4d05-9710-72ab1d7e162c`锛?  - `recipe_ingredients=0`
  - `recipe_only_ingredients` 涓ゆ潯锛歚楦¤泲 3 COUNT`銆乣beef 200 G`
- 鍘熷鏁伴噺/鍗曚綅娌℃湁琚浆鎹㈠悗鐨?canonical 鏁伴噺瑕嗙洊銆?
### C鈫扵RACKED 鐪熷疄 UI锛氶€氳繃锛堟樉寮忔槧灏勶級

鏉ユ簮涓汉鑿滆氨 `D1CToTrackedSource`锛堥鐩?2 鍕猴級锛?- 榛樿鏈€夋嫨锛?- 閫夋嫨 TRACKED锛?- 鏄惧紡閫夋嫨瀛楀吀椋熸潗 beef锛屽～鏁伴噺 2锛?- 瀵煎叆銆?
DB family recipe `9cb207d8-8639-4223-8568-f4b7858a6fa2`锛?- `recipe_ingredients=1`锛歜eef 2.000 G trackStock=true
- `recipe_only_ingredients=[]`

璇存槑锛氭湰杞獙璇佺殑鏄€淐鈫扵RACKED 蹇呴』鏄惧紡閫夋嫨骞堕€氳繃涓ユ牸鏍￠獙鈥濓紝鏄惧紡鏄犲皠鍒?beef 鑰屼笉鏄鐩愶紱涓嶆槸鍚嶇О鑷姩鍖归厤銆?
### 娣峰悎 UI锛氬凡閫氳繃锛堝墠涓€杞級

`D1MixedSource`/`D1FixSource` 瀹炴祴 2 TRACKED + 2 RECIPE_ONLY锛?- 楦¤泲 3 COUNT銆乥eef 200 G
- 椋熺洂 閫傞噺 + 璋冨懗銆侀叡娌?2 鍕?- 璇︽儏/缂栬緫鍣ㄥ弻绫诲瀷姝ｅ父銆?
### 瀵煎叆 UNKNOWN + 杩涚▼姝讳骸锛氬凡閫氳繃锛堝墠涓€杞級

- pending source=IMPORT锛?- 鍘?key `23dce605-...`銆乸ayload/fingerprint 鏈彉锛?- 閲嶈瘯鍙垱寤轰竴鏉★紱
- pending 娓呯悊銆?
### 涓汉蹇収锛欱1 宸叉敮鎸侊紝绾犳 D1 鎶ュ憡

- `HouseholdRecipeService.snapshot` 灏?RECIPE_ONLY 鍐欏叆 `user_recipes.ingredients`锛?  - name锛?  - quantity=amountText锛?  - unit=null锛?  - note锛?  - sourceText=amountText銆?- `HouseholdRecipeOnlyHttpIntegrationTest` 瑕嗙洊 snapshot 鍖呭惈椋熺洂/閫傞噺銆侀叡娌?2 鍕恒€侀鑿溿€?- 瀹為檯瀵煎叆鍚庣殑涓汉 snapshot 涔熸樉绀轰笂杩扮粨鏋勩€?- 涓嶉渶瑕佹柊澧炰釜浜?recipeOnlyIngredients 瀛楁/杩佺Щ銆?
### 绌哄簱瀛?
浠嶆湭瀹為檯鎵ц锛涚姸鎬佷笉鍙樸€?
### 360dp

璁剧疆 360脳640 / 160dpi 鍚庢鏌ヤ簡瀹跺涵鑿滆氨璇︽儏甯冨眬锛堥鏉愩€佸仛娉曘€佽惀鍏诲彲婊氬姩锛夛紱**鏈湪 360dp 鎵撳紑 ImportReviewDialog**锛屼篃鏈獙璇佺被鍨嬮€夋嫨/搴曢儴鎸夐挳/閿欒鎻愮ず銆傝嫳鏂囧瓧浣?娣辫壊/瀛椾綋鏀惧ぇ鏈墽琛屻€?
### 鏈疆娓呯悊

- 鍒犻櫎 `D1TrackedOnlySource`銆乣D1MixedSource`銆乣D1CToTrackedSource` 瀹跺涵 recipe/ingredients/snapshots銆?- 鏈湴 pending=0銆?- OurHome 鍘?6 閬撹彍璋憋紱movements=8銆?- Pinyin IME 鎭㈠锛孉DBKeyboard 鍗歌浇銆?
### 鏇存柊鍚庣殑缁撹

- P0 浠?TRACKED銆佹贩鍚堛€丄/B鈫扲ECIPE_ONLY銆丆鈫扵RACKED銆佸鍏?UNKNOWN 杩涚▼姝讳骸鍧囧凡瀹為檯閫氳繃锛圕鈫扵RACKED 鏄犲皠鍒版樉寮忛€夋嫨鐨?beef锛夈€?- 绌哄簱瀛樸€?60dp 瀵煎叆 Dialog銆佽处鍙?瀹跺涵/鎹熷潖 pending銆佽嫳鏂?瀛椾綋/娣辫壊浠嶆湭瀹屾垚銆?- Phase D 浠嶅缓璁潯浠跺叧闂紝涓嶅簲瀹ｇО瀹屾暣 D1 鐭╅樀閫氳繃銆?
