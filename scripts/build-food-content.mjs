import { mkdirSync, writeFileSync } from 'node:fs';

// Editorial source for the bundled offline edition. Verify each source before changing schedules.
const verified = '2026-11-22';
const sources = [];
function source(id, title, url) { sources.push({ id, title, url, verified }); return id; }
const can = 'https://nchfp.uga.edu/how/can/';
const veg = can + 'canning-vegetables-and-vegetable-products/';
const meat = can + 'preparing-and-canning-poultry-red-meats-and-seafoods/';
const fruit = can + 'canning-fruits-and-fruit-products/';
source('pressure', 'NCHFP / UGA — Using Pressure Canners', can + 'general-information/using-pressure-canners/');
source('bath', 'NCHFP / UGA — Using Boiling Water Canners', can + 'general-information/using-boiling-water-canners/');
source('home-storage', 'NCHFP — Storing Home Canned Foods', can + 'general-information/storing-home-canned-foods');
source('commercial', 'USDA FSIS — Food Product Dating', 'https://www.govinfo.gov/content/pkg/GOVPUB-A110-PURL-gpo15519/pdf/GOVPUB-A110-PURL-gpo15519.pdf');
source('commercial-chart', 'University of Nebraska Extension — Home Food Storage', 'https://food.unl.edu/free-resource/food-storage/');
source('dry-pack', 'Utah State Extension — Packaging Foods', 'https://extension.usu.edu/preserve-the-harvest/food-storage-packaging');
source('dry-moisture', 'Utah State Extension — Get the Most Out of Food Storage', 'https://extension.usu.edu/news_sections/home_family_and_food/get_the_most_of_food_storage');
source('dry-quality', 'Utah State Extension — Food Storage in Home', 'https://extension.usu.edu/preserve-the-harvest/research/food-storage-in-home');
source('dry-conditions', 'Utah State Extension — Food Storage Conditions', 'https://extension.usu.edu/preserve-the-harvest/research/storage-conditions');
source('brown-rice', 'Utah State Extension — Food Storage Booklet, brown rice', 'https://extension.usu.edu/preserve-the-harvest/files/Food-Storage-Booklet.pdf');
source('botulism', 'CDC — Home-Canned Foods', 'https://www.cdc.gov/botulism/prevention/home-canned-foods.html');
source('outage', 'FoodSafety.gov — Food Safety During Power Outage', 'https://www.foodsafety.gov/food-safety-charts/food-safety-during-power-outage');
source('water', 'CDC — Creating an Emergency Water Supply', 'https://www.cdc.gov/water-emergency/about/how-to-create-and-store-an-emergency-water-supply.html');
source('hygiene', 'CDC — Personal Hygiene During an Emergency', 'https://www.cdc.gov/water-emergency/safety/guidelines-for-personal-hygiene-during-an-emergency.html');
source('co', 'CDC — Carbon Monoxide Prevention', 'https://www.cdc.gov/carbon-monoxide/prevention/index.html');
source('firearm', 'CDC — Preventing Firearm Injury and Death', 'https://www.cdc.gov/firearm-violence/prevention/index.html');
source('potato-garden', 'UF/IFAS — Growing Potatoes in Florida', 'https://sfyl.ifas.ufl.edu/lawn-and-garden/potatoes-home-garden/');
source('potato-calendar', 'UF/IFAS Manatee — Sprouting Spuds, February 2025', 'https://sfyl.ifas.ufl.edu/media/sfylifasufledu/manatee/docs/pdfs/master-gardners/newsletters/February-2025---The-Garden-Bench.pdf');
source('sweet-calendar', 'UF/IFAS — Root Crop Production, Central Florida planting table', 'https://edis.ifas.ufl.edu/publication/CV300/pdf');
source('garden-calendar', 'UF/IFAS Pinellas — School Garden Seed Suggestions', 'https://sfyl.ifas.ufl.edu/media/sfylifasufledu/pinellas/lawn-and-garden/school-garden-seeds.pdf');
source('mulch', 'University of Minnesota Extension — Mulching for Soil and Garden Health', 'https://extension.umn.edu/garden-and-home/yard-and-garden/gardening-in-minnesota/mulching-for-soil-and-garden-health');
source('potato-soil', 'University of Minnesota Extension — Growing Potatoes', 'https://extension.umn.edu/garden-and-home/yard-and-garden/gardening-in-minnesota/growing-potatoes');

const products = [];
function canned(id, name, category, size, target, highAcid = false) {
  products.push({ id, name, category, size, target, kind: 'COMMERCIAL', unit: 'can',
    storageId: highAcid ? 'commercial-high' : 'commercial-low', noCook: true, qualityMonths: highAcid ? 18 : 24 });
}
canned('c-chicken','Canned chicken','Meat & seafood','12–13 oz',24);
canned('c-tuna','Chunk light tuna','Meat & seafood','5 oz',24);
canned('c-salmon','Canned salmon','Meat & seafood','14–15 oz',12);
canned('c-sardines','Sardines','Meat & seafood','3–4 oz',12);
canned('c-chili','Chili with beans','Complete meals','15–19 oz',24);
canned('c-stew','Beef stew','Complete meals','18–20 oz',12);
canned('c-soup','Hearty soup','Complete meals','18–20 oz',24);
canned('c-black-beans','Black beans','Beans','15 oz',12);
canned('c-kidney-beans','Kidney or pinto beans','Beans','15 oz',12);
canned('c-chickpeas','Chickpeas','Beans','15 oz',12);
canned('c-baked-beans','Baked beans','Beans','15–16 oz',12);
canned('c-green-beans','Green beans','Vegetables','14–15 oz',12);
canned('c-corn','Corn','Vegetables','15 oz',12);
canned('c-carrots','Carrots','Vegetables','14–15 oz',12);
canned('c-peas','Peas','Vegetables','15 oz',12);
canned('c-spinach','Spinach or mixed vegetables','Vegetables','14–15 oz',12);
canned('c-peaches','Peaches','Fruit','15 oz',12,true);
canned('c-pears','Pears','Fruit','15 oz',12,true);
canned('c-oranges','Mandarin oranges','Fruit','11–15 oz',12,true);
canned('c-pineapple','Pineapple or fruit cocktail','Fruit','15–20 oz',12,true);
canned('c-tomatoes','Diced tomatoes','Ingredients & starches','14–15 oz',12,true);
canned('c-tomato-sauce','Tomato sauce','Ingredients & starches','14–15 oz',12,true);
canned('c-potatoes','Canned potatoes','Ingredients & starches','14–15 oz',12);
const dry = [
 ['white-rice','White rice','lb',75,'dry-grain',false,true,20,24],
 ['brown-rice','Brown rice','lb',0,'brown-rice',false,true,45,6],
 ['dry-beans','Dry beans / lentils','lb',40,'dry-bean',false,true,null,null],
 ['lentils','Lentils','lb',0,'dry-bean',false,true,null,null],
 ['pasta','Pasta','lb',35,'dry-grain',false,true,null,24],
 ['oats','Rolled oats','lb',30,'dry-grain',false,true,null,6],
 ['wheat','Wheat berries','lb',0,'dry-grain',false,true,null,null],
 ['flour','White flour','lb',0,'flour',false,true,null,12],
 ['cornmeal','Cornmeal','lb',0,'flour',false,true,null,12],
 ['sugar','Sugar','lb',0,'sugar-salt',true,false,0,null],
 ['salt','Salt','lb',0,'sugar-salt',true,false,0,null],
 ['honey','Honey','jar',0,'honey',true,false,0,null],
 ['peanut-butter','Peanut butter','jar',12,'oils',true,false,0,null],
 ['oil','Cooking oil','gal',3,'oils',false,false,null,null],
 ['milk','Nonfat powdered milk','lb',0,'milk',false,true,null,null],
 ['freeze-dried','Freeze-dried food','pouch',0,'prepared',false,true,null,null],
 ['dried-fruit','Dehydrated fruit','pouch',0,'dried-produce',true,false,0,null],
 ['dried-vegetables','Dehydrated vegetables','pouch',0,'dried-produce',false,true,null,null],
 ['ready-meal','Shelf-stable ready-to-eat meal','pouch',0,'prepared',true,false,0,null],
 ['other','Other shelf-stable staple','package',0,'prepared',false,false,null,null],
];
for (const [id,name,unit,target,storageId,noCook,waterNeeded,cookingMinutes,qualityMonths] of dry) {
  products.push({id,name,unit,target,storageId,noCook,waterNeeded,cookingMinutes,qualityMonths,
    kind:id==='ready-meal'?'READY':'DRY',category:'Dry goods & staples',size:id==='peanut-butter'?'16 oz per jar':'',
    portion:'Enter the label serving size and servings per stock unit. Cooking times follow your package.',
    nutrition:'Calories and protein vary by product. Coverage uses the nutrition you enter, with missing labels shown as incomplete.'});
}

const storage = [];
function advice(id,title,container,quality,oxygen,extra={}) {
 storage.push({id,title,container,quality,oxygen,
  conditions:'Store cool, dry, dark, off the floor, and protected from pests. Monitor heat and humidity during Tampa power outages.',
  mylar:'Food-safe foil laminate provides a moisture and oxygen barrier when properly heat sealed.',
  bucket:'Use a clean food-grade bucket and compatible lid for physical protection around sealed food bags. Keep away from chemicals.',
  vacuum:'Vacuum packaging preserves quality only when suitable for the food. Moist food still needs validated preservation or refrigeration.',
  discard:'Discard moldy, wet, insect-damaged, leaking, or rancid food. Follow recall and product instructions.',
  sources:['dry-pack','dry-moisture','dry-conditions'],...extra});
}
advice('commercial-low','Commercial low-acid cans','Keep in original, intact packaging.','USDA general best-quality window 2–5 years for meat, fish, beans, vegetables, and most soups. Manufacturer dates and conditions take priority.','Keep the original sealed can. Additional oxygen absorbers are unnecessary.',
 {mylar:'Original can is the preservation barrier.',bucket:'Shelving must carry the load. Keep cans dry.',vacuum:'Keep commercially sealed packaging intact.',discard:'Discard swollen, leaking, deeply dented at seams, or badly rusted cans. Never taste suspect food.',sources:['commercial','commercial-chart']});
advice('commercial-high','Commercial high-acid cans','Original intact cans or jars.','USDA general best-quality window 12–18 months for tomatoes and fruit. Check manufacturer guidance.','Use the original sealed packaging.',
 {mylar:'Original packaging is sufficient.',bucket:'Protect jars from breakage and cans from moisture.',vacuum:'Keep original seals intact.',sources:['commercial','commercial-chart']});
advice('home','Home-canned food','Proper canning jars, tested process, sound seals. Remove bands after checking seals.','Plan to rotate within one year for best quality. This is a quality target, not a food-specific safety expiration.',
 'Use the tested canning process, not an oxygen absorber.',{conditions:'Preferred 50–70°F. Avoid storage above 95°F, dampness, sunlight, and freezing that breaks seals.',mylar:'Canning jars are the preservation container.',bucket:'Protect jars on stable shelves.',vacuum:'An intact seal cannot prove correct processing.',discard:'Discard food of uncertain processing history, damaged or leaking jars, bulging lids, spurting contents, or mold. Never taste to test safety.',sources:['home-storage','botulism']});
advice('dry-grain','Low-fat dry grains and pasta','Food-safe moisture-proof jars, cans, or properly sealed foil laminate bags.','Use product dates. USU household planning examples at 70°F include pasta two years and hot cereals six months. Packaging and heat change quality.',
 'Appropriate only for genuinely dry products, approximately 10% moisture or less. Moist reduced-oxygen storage can permit botulism.');
advice('dry-bean','Dry beans and lentils','Dry, sealed food-safe container; foil laminate within a food-grade bucket for larger quantities.','Use product dates and rotate foods you cook regularly. Older beans can take longer to soften; budget water and fuel.',
 'Use only with sufficiently dry food in an appropriate barrier package. Never seal cooked or soaked beans this way.');
advice('brown-rice','Brown rice','Airtight container, cool and dark.','USU gives up to six months at 70°F. Its bran oils limit quality. Refrigeration may extend quality only while reliable.',
 'Removing oxygen does not remove the bran oil or its rancidity risk.',{sources:['brown-rice','dry-pack']});
advice('flour','Flour and cornmeal','Airtight, food-safe container away from heat and pests.','USU examples at 70°F give white enriched flour and cornmeal one year. Whole-grain flours contain more oil and need quicker rotation.',
 'Only for sufficiently dry, suitable products. Whole-grain oils still limit quality. Never eat flour raw.',{sources:['dry-quality','dry-pack','dry-moisture']});
advice('sugar-salt','Sugar and salt','Dry airtight, odor-proof food-grade containers.','Follow product quality guidance and protect from moisture and contamination.',
 'Generally unnecessary. Keep dry to prevent caking.');
advice('honey','Honey','Tightly closed glass or food-safe container.','Follow the label. Crystallization alone is not evidence of spoilage.',
 'Do not add oxygen absorbers to liquids or moist foods.',{mylar:'Keep honey in its suitable original container.',vacuum:'Not needed for sealed honey.',sources:['dry-pack']});
advice('oils','Peanut butter and cooking oils','Original tightly closed, light-protective packaging; smaller containers help rotation.','Use manufacturer dates. Oils and nut butters can become rancid; no multidecade storage claim is made.',
 'Do not use as a universal life-extension method for oils or moist foods.',{mylar:'Do not repackage liquids in improvised bags.',bucket:'Avoid placing loose oil directly into general bulk-storage buckets.'});
advice('milk','Nonfat powdered milk','Dry, opaque airtight food-safe container; keep the original label.','Follow the manufacturer and rotate. Reconstituted milk needs safe water and dependable refrigeration.',
 'May be suitable for dry nonfat milk in a proper sealed package. Follow the packager instructions.');
advice('dried-produce','Dried fruits and vegetables','Completely dried and cooled food, moisture-proof packaging in small portions.','Follow the product or validated drying guidance. Moisture uptake and mold can shorten quality rapidly in humid weather.',
 'Only when the food is dry enough for reduced-oxygen storage. Home-dried fruit may retain too much moisture.');
advice('prepared','Prepared shelf-stable meals','Original package; distinguish shelf-stable products from those labeled Keep Refrigerated.','Manufacturer-specific dates, preparation, water needs, and storage temperatures apply.',
 'Use original preservation packaging. Do not improvise reduced-oxygen storage.');

const recipes = [];
const dial = [{maxFeet:2000,psi:11},{maxFeet:4000,psi:12},{maxFeet:6000,psi:13},{maxFeet:8000,psi:14}];
const weighted = [{maxFeet:1000,psi:10},{maxFeet:8000,psi:15}];
function pressure(id,food,category,slug,pack,preparation,headspace,liquid,times,extra={}) {
 source(id,`NCHFP — ${food}`, (category==='Vegetables'||category==='Beans'||category==='Soups'?veg:meat)+slug+'/');
 recipes.push({id,food,category,method:'PRESSURE',pack,preparation,peeling:'As described in preparation',precook:pack==='HOT'?'Required as described':'Raw pack only as described',headspace,liquid,
  saltAcid:'Salt is optional for these plain low-acid foods. Preserve exact preparation and liquid requirements.',
  schedules:Object.entries(times).map(([jar,min])=>({jar,minutes:[min]})),dial,weighted,waterBathBands:[],
  warnings:['Use a tested pressure canner and the exact food preparation. Follow the bundled pressure-canning steps.'],sources:[id],version:verified,verified:true,...extra});
}
for(const pack of ['HOT','RAW']) {
 pressure('green-beans-'+pack.toLowerCase(),'Green or wax beans · '+pack.toLowerCase()+' pack','Vegetables','beans-snap-and-italian-pieces-green-and-wax',pack,
  ['Select sound tender pods. Wash and trim; leave whole or cut into 1-inch pieces.',pack==='HOT'?'Boil in water for 5 minutes and pack loosely.':'Pack raw beans firmly.'], '1 inch',pack==='HOT'?'Hot cooking liquid':'Boiling water',{PINT:20,QUART:25});
 pressure('carrots-'+pack.toLowerCase(),'Carrots · '+pack.toLowerCase()+' pack','Vegetables','carrots-sliced-or-diced',pack,
  ['Wash, peel, rewash, and slice or dice.',pack==='HOT'?'Cover with boiling water and simmer 5 minutes. Pack hot.':'Pack raw carrots tightly.'],'1 inch',pack==='HOT'?'Hot cooking liquid or water':'Hot water',{PINT:25,QUART:30});
}
pressure('white-potato-cubes','White potatoes · ½-inch cubes','Vegetables','potatoes-white-cubed-or-whole','HOT',
 ['Wash and peel. Hold cut pieces in ascorbic-acid solution prepared according to its label. Drain.','Cut into ½-inch cubes, boil 2 minutes, and drain. Pack hot.'],'1 inch','FRESH boiling water. Discard the starchy cooking water.',{PINT:35,QUART:40});
pressure('white-potato-whole','White potatoes · whole 1–2 inch','Vegetables','potatoes-white-cubed-or-whole','HOT',
 ['Use sound 1–2 inch diameter potatoes. Wash and peel; hold in label-prepared ascorbic-acid solution.','Drain, boil whole potatoes 10 minutes, drain, and pack hot.'],'1 inch','FRESH boiling water, not starchy cooking water.',{PINT:35,QUART:40});
pressure('sweet-potato','Sweet potatoes · pieces or small whole','Vegetables','potatoes-sweet-pieces-or-whole','HOT',
 ['Wash and boil or steam 15–20 minutes until partly soft. Peel.','Cut into uniform pieces as needed and pack hot.'],'1 inch','Fresh boiling water; this edition uses water rather than syrup.',{PINT:65,QUART:90},{warnings:['Keep pieces intact. Do not mash, puree, or dry pack.']});
pressure('asparagus','Asparagus · hot pack','Vegetables','asparagus-spear-or-pieces','HOT',
 ['Wash, remove scales and tough stems, and wash again. Leave spears or cut 1-inch pieces.','Boil 2–3 minutes and pack loosely.'],'1 inch','Boiling water',{PINT:30,QUART:40});
pressure('beets','Beets · hot peeled pieces','Vegetables','beets-whole-cubed-or-sliced','HOT',
 ['Leave 1 inch of stem and the roots during initial washing. Boil 15–25 minutes until skins slip.','Cool only enough to handle; peel, trim roots and stems. Keep small beets whole or cut larger beets into ½-inch cubes or slices. Pack while hot.'],'1 inch','Fresh boiling water, not the original boiling liquid.',{PINT:30,QUART:35});
pressure('corn','Corn · whole kernels, hot pack','Vegetables','corn-whole-kernel','HOT',
 ['Husk, remove silk, and wash. Blanch ears 3 minutes. Cut kernels about ¾ of their depth without scraping the cob.','Add 1 cup hot water per quart of kernels, bring to boil, simmer 5 minutes. Pack corn with liquid.'],'1 inch','Cooking liquid',{PINT:55,QUART:85});
pressure('peas','Green or English peas · hot pack','Vegetables','peas-green-or-english-shelled','HOT',
 ['Choose sound young peas, shell and wash.','Boil in water 2 minutes. Pack loosely with hot liquid.'],'1 inch','Hot cooking liquid',{PINT:40,QUART:40});
pressure('pumpkin','Pumpkin or winter squash · 1-inch cubes','Vegetables','pumpkins-and-winter-squash-cubed','HOT',
 ['Choose mature hard-rind pumpkin or winter squash. Wash, remove seeds, peel, and cut into 1-inch cubes.','Boil cubes 2 minutes in water and pack hot.'],'1 inch','Hot cooking liquid',{PINT:55,QUART:90},{warnings:['Only intact cubes. No home-canning schedule for puree, mashed pumpkin, or pumpkin butter is supplied.']});
pressure('dry-beans','Pinto, black, kidney beans, chickpeas · hydrated','Beans','beans-or-peas-shelled-dried-all-varieties','HOT',
 ['Sort and wash dried beans. Cover with boiling water, boil 2 minutes, remove from heat, soak 1 hour, and drain.','Cover with fresh water and boil 30 minutes. Pack beans and hot cooking water.'],'1 inch','Cooking water',{PINT:75,QUART:90},{warnings:['Hydration and the 30-minute boil are required. Do not pack dry beans straight into jars.']});
for(const bones of [false,true]) for(const pack of ['HOT','RAW']) {
 pressure(`poultry-${bones?'bone-in':'boneless'}-${pack.toLowerCase()}`,`Chicken / turkey ${bones?'with bones':'boneless'} · ${pack.toLowerCase()}`,'Meat & poultry','chicken-or-rabbit',pack,
  ['Use properly chilled, freshly dressed poultry. Trim excess fat and cut pieces to fit.',pack==='HOT'?'Boil, steam, or bake until about two-thirds done. Pack with hot broth.':'Loosely fill with raw meat. Add no liquid.'],'1¼ inches',pack==='HOT'?'Hot broth':'No added liquid',bones?{PINT:65,QUART:75}:{PINT:75,QUART:90},
  {warnings:['Keep meat cold before processing. Raw-pack meat may not remain covered by its own juices.']});
}
for(const pack of ['HOT','RAW']) pressure(`meat-chunks-${pack.toLowerCase()}`,`Beef / pork / venison chunks · ${pack.toLowerCase()}`,'Meat & poultry','meat-strips-cubes-or-chunks',pack,
 ['Use high-quality chilled meat, remove excess fat and large bones.',pack==='HOT'?'Precook until rare by browning, roasting, or stewing. Pack with boiling liquid.':'Fill with raw pieces. Do not add liquid.'],'1 inch',pack==='HOT'?'Boiling broth, water, or meat drippings':'No added liquid',{PINT:75,QUART:90});
pressure('ground-meat','Ground beef / pork / venison · browned','Meat & poultry','meat-ground-or-chopped','HOT',
 ['Use fresh chilled meat. For ground venison, the source calls for one part high-quality pork fat to three or four parts venison before grinding.','Brown ground meat or formed patties/balls. Remove excess fat and pack.'],'1 inch','Boiling broth, tomato juice, or water',{PINT:75,QUART:90});
pressure('chicken-broth','Chicken or turkey broth','Meat & poultry','chicken-or-turkey-stock','HOT',
 ['Cover carcass bones with water and simmer 30–45 minutes until small meat tidbits release.','Remove bones, cool under safe refrigeration, and discard excess fat. Reboil broth and pack hot.'],'1 inch','The boiling broth',{PINT:20,QUART:25},
 {warnings:['The source includes a cooling step. Maintain food-safe temperatures; if reliable cooling is unavailable, do not improvise this step.']});
pressure('beef-broth','Beef broth','Meat & poultry','meat-stock','HOT',
 ['Crack fresh trimmed beef bones, rinse and cover with water. Simmer covered 3–4 hours.','Remove bones, cool under safe refrigeration and discard excess fat. Reheat to boiling.'],'1 inch','The boiling broth',{PINT:20,QUART:25},
 {warnings:['This stock process allows only small meat tidbits. It is not a chunky stew schedule. Safe cooling is required.']});
pressure('fish-pint','Salmon and listed fatty fish · pints','Seafood','fish-pint-jars-usda','RAW',
 ['Use bluefish, mackerel, salmon, steelhead or trout. Eviscerate within 2 hours of catch and keep cleaned fish on ice.','Remove head, tail, fins, scales and blood. Cut into 3½-inch lengths. Pack skin toward glass.'],'1 inch','No added liquid',{PINT:100},
 {warnings:['This process is for the listed fish, excludes tuna, and supports pint jars only. No quart option.']});
pressure('tuna','Tuna · precooked, water pack','Seafood','tuna','HOT',
 ['Keep fish on ice; remove viscera and wash. Bake at 350°F for 1 hour to 165–175°F internal temperature.','Refrigerate cooked fish overnight. Remove skin, bones, blood vessels and dark flesh. Cut to jar length and pack firmly without crushing.'],'1 inch','Water',{HALF_PINT:100,PINT:100},
 {warnings:['Dependable overnight refrigeration is part of this selected process. No quart schedule is supplied.']});
for(const seafood of [false,true]) pressure(`soup-${seafood?'seafood':'meat-vegetable'}`,seafood?'Tested soup procedure · includes seafood':'Tested soup procedure · vegetables / meat','Soups','soups','HOT',
 ['Use only ingredients with their own tested canning recommendations. Prepare each as its own hot-pack recipe requires. Cook meat until tender and remove bones.','For each cup of dried beans or peas, use 3 cups water, boil 2 minutes, stand off heat 1 hour, reboil and drain.','Combine prepared solids and broth, tomatoes or water to cover. Boil 5 minutes. Half-fill jars with solids, then add liquid.'],'1 inch','Broth, tomatoes, or water',seafood?{PINT:100,QUART:100}:{PINT:60,QUART:75},
 {warnings:['No pasta, rice, flour, dairy, cream, or other thickeners. Beans must be rehydrated. Only the specified half-solids, half-liquid pack is supported.']});

function bath(id,food,category,url,pack,preparation,headspace,liquid,schedules,bands=[1000,3000,6000,20000],extra={}) {
 source(id,`NCHFP — ${food}`,url);
 recipes.push({id,food,category,method:'WATER_BATH',pack,preparation,peeling:'As specified in preparation',precook:pack==='HOT'?'As specified':'As specified',
  headspace,liquid,saltAcid:'Preserve the exact tested ingredients.',schedules:Object.entries(schedules).map(([jar,minutes])=>({jar,minutes})),
  dial:[],weighted:[],waterBathBands:bands,warnings:['Use the exact recipe and the bundled boiling-water canning instructions.'],sources:[id],version:verified,verified:true,...extra});
}
bath('peaches','Yellow peaches · hot halves or slices','Fruits',fruit+'peaches-halved-or-sliced/','HOT',
 ['Use yellow-flesh peaches only. Dip 30–60 seconds in boiling water, briefly cool, peel, pit and halve or slice.','Bring drained peaches to a boil in water. Pack hot fruit and liquid.'],'½ inch','Hot water',{PINT:[20,25,30,35],QUART:[25,30,35,40]},undefined,{warnings:['White-flesh peaches are excluded. No verified home-canning procedure for them is provided.']});
bath('pears','European pears · hot halves','Fruits',fruit+'pears-halved/','HOT',
 ['Wash, peel, halve lengthwise and core ripe European pears.','Boil drained halves in water for 5 minutes and pack hot.'],'½ inch','Hot water',{PINT:[20,25,30,35],QUART:[25,30,35,40]},undefined,{warnings:['Asian pears require a different acidified recipe. This selection excludes them.']});
bath('apples','Apples · hot slices','Fruits',fruit+'apples-sliced/','HOT',
 ['Wash, peel and core. Slice into label-prepared ascorbic-acid solution, then drain.','Use 1 pint water per 5 lb apple slices. Boil 5 minutes while stirring and pack hot.'],'½ inch','Hot cooking liquid',{PINT:[20,25,30,35],QUART:[20,25,30,35]});
bath('berries','Blueberries, currants, gooseberries or huckleberries · hot','Fruits',fruit+'berries-whole/','HOT',
 ['Use only these four named berries. Wash small quantities, drain, remove caps and stems. Snip gooseberry heads and tails.','Heat berries in boiling water 30 seconds and drain. Add ½ cup hot juice to each jar, fill berries and cover with hot juice.'],'½ inch','Hot juice',{PINT:[15,20,20,25],QUART:[15,20,20,25]});
bath('tomatoes','Crushed tomatoes · acidified, no added liquid','Tomatoes',can+'how-do-i-can-tomatoes/crushed-tomatoes-with-no-added-liquid/','HOT',
 ['Wash, blanch 30–60 seconds, cool briefly, peel, core, trim damaged portions and quarter. Crush and heat the first one-sixth.','At boiling, gradually stir in the remaining quarters. Boil gently 5 minutes once all are added. Pack immediately.'],'½ inch','Own juice only',{PINT:[35,40,45,50],QUART:[45,50,55,60]},undefined,
 {saltAcid:'Per pint add 1 tbsp bottled lemon juice FROM CONCENTRATE or ¼ tsp citric acid. Per quart use 2 tbsp or ½ tsp. Acid is required.',sources:['tomatoes','tomato-acid'],warnings:['No added water, vegetables, starch, or changed ingredient proportions. Acidification is required.']});
source('tomato-acid','NCHFP — Tomato Acidification Directions',can+'how-do-i-can-tomatoes/tomato-acidification-directions/');
bath('berry-jam','Berry jam · no added pectin','Jams & jellies','https://nchfp.uga.edu/how/make-jam-jelly/jams/berry-jams-without-pectin/','HOT',
 ['Use 9 cups crushed berries and 6 cups sugar. Sterilize empty jars before filling.','Combine washed, crushed berries and sugar. Heat slowly to dissolve sugar, then boil rapidly, stirring as it thickens, until the mixture reaches the desired jellying point. Fill hot sterile jars.'],'¼ inch','None added',{HALF_PINT:[5,10,15],PINT:[5,10,15]},[1000,6000,20000],
 {saltAcid:'Keep the 9-cup fruit to 6-cup sugar proportions.',warnings:['Do not reduce sugar or add peppers, dairy, or other low-acid ingredients. Empty jars require boiling sterilization for a 5-minute process.']});
bath('apple-jelly','Apple jelly · no added pectin','Jams & jellies','https://nchfp.uga.edu/how/make-jam-jelly/jellies/apple-jelly-without-pectin/','HOT',
 ['For juice use about 3 lb tart apples, ¼ underripe and ¾ ripe, with 3 cups water. Remove stems/blossoms, leave peel and cores, cut small, boil then simmer covered 20–25 minutes and strain through a jelly bag.','Measure 4 cups juice and 3 cups sugar. Boil to 8°F above the local water boiling point or until jelly sheets from a spoon. Skim and fill hot sterile jars.'],'¼ inch','Measured juice',{HALF_PINT:[5,10,15],PINT:[5,10,15]},[1000,6000,20000],
 {warnings:['Keep exact proportions. Sterilize empty jars before a 5-minute process. No quart schedule.']});
bath('dill-pickles','Quick fresh-pack dill pickles','Pickles',can+'canning-pickled-products/quick-fresh-pack-dill-pickles/','RAW',
 ['Use 8 lb pickling cucumbers 3–5 inches long. Wash, cut 1/16 inch off blossom ends, leave ¼-inch stems. Soak 12 hours in 2 gal water with ¾ cup canning salt, then drain.','Boil 1½ qt vinegar (5% acidity), 2 qt water, ½ cup canning salt, ¼ cup sugar and 2 tbsp whole mixed pickling spice in a cloth bag.','Pack cucumbers into pint jars. Add 1 tsp mustard seed and 1½ heads dill or 1½ tsp dill seed per pint. Cover with boiling pickling liquid.'],'½ inch','Exact boiling pickling solution',{PINT:[10,15,20]},[1000,6000,20000],
 {saltAcid:'Use 5% acidity vinegar and the exact recipe ratios. This edition supports pints only.',warnings:['No substitution of vinegar strength, reduction of acid, or untested vegetable additions.']});
for(const [id,food,category,reason] of [
 ['white-peaches','White-flesh peaches','Fruits','NCHFP reports no tested home-canning process for white peaches. Use another validated preservation method.'],
 ['puree','Pumpkin puree / pumpkin butter','Vegetables','The tested recipe is for cubes only. Puree changes heat transfer.'],
 ['vegetable-broth','Custom vegetable broth','Soups','A plain meat-stock schedule cannot be assumed suitable for a custom vegetable broth. Choose an exact tested recipe.'],
 ['custom-recipe','Custom food, cream soup, or unlisted seafood','Other','A recipe for a similar food is insufficient evidence. Ask Extension and obtain the exact tested preparation.'],
]) recipes.push({id,food,category,method:'UNVERIFIED',pack:'UNSUPPORTED',preparation:[reason],peeling:'Unverified',precook:'Unverified',headspace:'Unverified',liquid:'Unverified',saltAcid:'Unverified',warnings:['No verified home-canning procedure available in this offline edition.'],sources:['botulism','pressure'],verified:false,version:verified});

const guides = [];
function guide(id,title,section,paragraphs,refs) { guides.push({id,title,section,paragraphs,sources:refs}); }
guide('offline','Using Food & Water offline','Getting started',[
 'All guides, recipe tables, pantry calculations, shopping lists, batches and timers are installed with the app. Links identify sources and open when connected. Reading the source metadata requires no network.',
 'Choose an editable planning profile. The Tampa baseline is 7 people, 30 days, 2,000 calories and 1 gallon water per person per day. That is 420,000 calories and 210 gallons. Heat, illness, pets, cooking and toilet flushing can increase water needs.',
 'Targets start with 324 cans and selected dry staples. Targets are shopping suggestions. Add already-owned or purchased lots to record real food. Enter serving calories, protein and servings per stock unit from labels. Missing nutrition stays visibly incomplete.',
 'Pantry lots share the existing Supplies quantities. Consumption updates both screens. Profiles change planning assumptions without changing stock. JSON export includes food data and water supplies. Import adds missing IDs and keeps existing entries on conflicts. The full Outpost backup includes the entire Room database.',
 'Keep the canner under direct supervision. Timers cannot measure pressure or temperature. Countdown deadlines persist across app restarts on the same device boot. After a restart of the phone or import from another device, verify your separate timer and treat an uncertain process as compromised.',
],['water','pressure']);
guide('commercial-life','Commercial cans and quality dates','Shelf life',[
 'Low-acid commercially canned meat, fish, beans and most vegetables have a USDA general best-quality window of 2–5 years. High-acid tomatoes and fruits generally have 12–18 months. Keep manufacturer labels and check product-specific storage instructions.',
 'These are quality recommendations for sound unopened cans stored correctly. Keep foods labeled Keep Refrigerated under reliable refrigeration. Damaged packaging or unsafe storage changes the decision regardless of the date.',
 'After opening, follow label and food-specific guidance. Without dependable refrigeration, open only what the group will eat at the meal and discard perishable leftovers beyond safe holding limits.',
],['commercial','commercial-chart','outage']);
guide('home-life','Home-canned quality and inspection','Shelf life',[
 'Use one year as the general rotation target for properly processed home-canned foods. Preferred storage is 50–70°F in a clean, dark, dry place. Avoid temperatures above 95°F. Heat in Tampa garages and sheds can shorten quality and damage food.',
 'Remove screw bands after cooling and checking seals, wash and dry jars, then date and label them. A sealed lid cannot establish that an untested or interrupted process was safe. Quality dates do not certify safety.',
 'Discard suspect food. Do not taste it. Botulinum toxin may have no visible sign, smell or taste. CDC advises boiling properly home-canned low-acid foods and tomatoes before eating. Below 1,000 ft boil 10 minutes, with one additional minute for each extra 1,000 ft. This does not rescue improperly canned or suspect food.',
],['home-storage','botulism']);
guide('pressure-workflow','Pressure-canning equipment and process','Canning',[
 'Use a pressure canner validated for the USDA process, large enough for at least four upright quart jars on a rack. Ordinary pressure cookers and electric multicookers cannot be substituted. Check dial-gauge accuracy annually and follow the canner and lid manufacturers.',
 'Follow the selected preparation, jar size, liquid and headspace. Remove bubbles, wipe rims, fit new flat lids and bands as the manufacturer directs. Use the canner rack and the recipe/manufacturer water amount. Keep jars upright.',
 'Exhaust a steady stream of steam through the open vent for 10 minutes. Fit the weight or close the vent as directed. Start the processing timer only after the selected pressure is reached. Maintain it continuously. If it drops, restore pressure and restart the entire processing time.',
 'After processing, switch off heat and let pressure fall naturally. Follow the canner lock and gauge indicators. After complete depressurization, remove the weight or open the petcock and wait 10 minutes before carefully opening away from your face. Never force-cool or force the lid.',
 'Remove jars upright onto a towel or rack and leave undisturbed 12–24 hours. Check seals after cooling. Failed seals and uncertain processes remain flagged. Follow current NCHFP directions for corrective handling. Refrigeration options require dependable cooling.',
],['pressure','home-storage']);
guide('bath-workflow','Boiling-water canning','Canning',[
 'Use boiling-water canning only with the tested acid-food, acidified, jam or pickle recipe. Fully cover jars with water at least 1 inch above the tops, or 2 inches for processing over 30 minutes.',
 'Bring water to a full boil before timing. Maintain a boil and the required water cover throughout. If boiling stops, restore it and restart the full process. Afterward, turn off heat, remove the canner lid and wait 5 minutes before lifting jars.',
 'Cool jars upright and undisturbed 12–24 hours before checking seals. Sterilize empty jars for processes under 10 minutes by boiling 10 minutes below 1,000 ft and adding 1 minute per additional 1,000 ft. Follow the verified recipe and jar/lid instructions.',
],['bath','home-storage']);
guide('storage-setup','Bulk packaging, pests and moisture','Storage',[
 'A properly sealed food-safe foil laminate bag limits oxygen and moisture transmission. A food-grade bucket adds handling and pest protection, but rodents can still damage plastics. Divide stocks into portions that can be used after opening.',
 'USU warns against oxygen absorbers for moist foods. Reduced-oxygen dry storage requires foods at about 10% moisture or less. Cooked beans, fresh vegetables, moist dried foods, oils and other unsuitable foods need a different method. Vacuum sealing does not make food shelf-stable.',
 'Keep white rice distinct from brown rice and whole-grain flour. The latter contain oils that can turn rancid. Keep packages dry, away from chemicals and light. Inspect for condensation, pests, mold and damage.',
 'Use first-in, first-out rotation. Record actual location, approximate temperature and humidity. Canned food must avoid freezing that damages seals. Capacity calculations use entered package volume, not jar food volume or guessed shelf load.',
],['dry-pack','dry-moisture','dry-conditions','brown-rice']);
guide('menu-guide','Meals, water and cooking fuel','Meals',[
 'The seven-day menu is an editable example. It is not a complete nutritional prescription. Add foods for allergies, age, illness, infant feeding and dietary needs. Include varied proteins, fruits, vegetables, carbohydrates and fats. Never give honey to infants under one year.',
 'Meal calories and protein are calculated from the pantry labels entered for its ingredients. They remain incomplete while labels are missing. Fractional can portions are planning quantities. Prepare a group batch that uses whole packages and serve promptly when refrigeration is unavailable.',
 'Water volumes and burner minutes are editable planning assumptions, not canning instructions. Package cooking directions control. Set stove kW or fuel grams/hour measured for your own burner to estimate energy or fuel. Beans can need substantial water and fuel. Plan commercial ready-to-eat food for the first outage days.',
],['commercial-chart','outage','dry-quality']);
guide('garden-potatoes','Central Florida winter potatoes','Garden',[
 'UF/IFAS Manatee guidance places Irish potatoes in January–February for Central Florida. Use certified disease-free seed potatoes suited to the region. Grocery potatoes may carry disease or sprout inhibitors. Use clean planting material and rotate crop families.',
 'Choose a sunny site and well-drained soil. Raised beds help drainage in small yards. Check moisture below the surface, irrigate as the soil needs, and keep developing tubers covered from light. UF/IFAS warns against eating green potatoes.',
 'Straw mulch can reduce evaporation and weeds. It supplements adequate soil, nutrients, drainage and water. A shallow planting/no-dig trial needs enough cover to prevent greening and monitoring for pests and wet spots. It is not a guaranteed yield method for Florida heat or floods.',
 'Record harvest weight and allocate fresh use, preservation, spoilage and suitable future planting. Retain only healthy stock when appropriate to the variety and local disease guidance. Keep diseased tubers out of future planting.',
],['potato-calendar','potato-garden','potato-soil','mulch']);
guide('garden-season','Sweet potatoes, beans and small gardens','Garden',[
 'UF/IFAS Root Crop Production lists Central Florida sweetpotato planting February–June. Use healthy disease-free slips from a reputable source, suitable soil and space for vines. Adjust timing for weather and local Extension advice.',
 'The Pinellas school garden guide gives pole beans August–September or February–March, and carrots August or January. These are local planning windows. Summer southern peas and okra suit warm conditions better than many cool-season vegetables.',
 'Choose raised beds or a small sunny plot with accessible water and drainage. Plant manageable areas in succession. Keep seeds labeled with variety and harvest year, dry and protected from pests and heat. Hybrid seed may not reproduce the original variety consistently.',
 'Harvest is extra supply rather than guaranteed emergency calories. Eat fresh when practical, choose a tested preservation method for surplus, and record actual yields. A potato harvest can link to the verified peeled, hot-pack recipe without forcing all produce into canning.',
],['sweet-calendar','garden-calendar','potato-garden']);
guide('safe-cooking','Cooking, refrigeration and clean water','Safety',[
 'Operate portable generators outdoors at least 20 feet from doors, windows and vents, with exhaust directed away. Use battery-backed carbon monoxide alarms. Keep outdoor stoves and grills outside and follow equipment clearances.',
 'Use smoke alarms and an accessible extinguisher rated for likely fires. Keep a clear exit, supervise flames, and keep children away from hot stoves and canners. Use the manufacturer-approved burner for the canner.',
 'A closed refrigerator keeps food safe for about 4 hours without power. A closed full freezer holds temperature about 48 hours, half full about 24. Check food-specific guidance. Discard perishable food held above 40°F for over 2 hours, or over 1 hour in conditions above 90°F.',
 'Wash hands with soap and safe water before food and after handling waste. Keep drinking water separate. Use the Outpost water and outage calculators, and follow current local advisories.',
],['co','outage','hygiene']);
guide('home-security','Household accident prevention','Safety',[
 'Use working locks, practical lighting, alerts, a household contact plan and accessible evacuation routes. Keep paths and exits usable for children and adults with limited mobility.',
 'Keep firearms unloaded and locked, with ammunition secured separately. Store keys and combinations away from children and unauthorized users. Keep ammunition dry, in suitable manufacturer packaging and away from heat.',
 'Injurious booby traps can harm family members, neighbors and emergency responders and can create serious legal liability. Use locks, alarms, observation and evacuation planning for household security.',
],['firearm']);

const equipment = [];
const canningEquipment = ['Validated pressure canner and jar rack','Wide-mouth quart jars','Wide-mouth pint jars','New flat canning lids','Reusable screw bands','Jar lifter','Canning funnel','Headspace gauge','Bubble remover','Independent timer','Labels','Cleaning supplies','Official tested recipe reference'];
const pantryEquipment = ['Food-grade 5-gallon buckets','Compatible bucket lids','Food-safe Mylar bags','Food-appropriate oxygen absorbers','Heat sealer','Load-rated shelving','Temperature / humidity monitor','Manual can opener'];
for(const [group,items] of [['Canning',canningEquipment],['Pantry',pantryEquipment]]) items.forEach((name,index)=>equipment.push({id:group.toLowerCase()+'-'+index,name,group,desired:1,priority:name==='Manual can opener'?'High':'Normal',notes:name==='Food-appropriate oxygen absorbers'?'Only for suitable low-moisture dry foods. See packaging guide.':''}));
const meals=[];
function meal(day,id,title,type,ingredients,waterLitersPerPerson,burnerMinutes,instructions) {
 meals.push({day,id,title,type,ingredients:ingredients.map(([productId,unitsPerPerson])=>({productId,unitsPerPerson})),waterLitersPerPerson,burnerMinutes,instructions});
}
for(let day=1;day<=7;day++) {
 meal(day,`d${day}-breakfast`,'Oats, peanut butter and fruit','Low-water', [['oats',0.12],['peanut-butter',0.08],['c-peaches',0.25]],0.18,8,'Cook oats according to the package using safe water. Add peanut butter and fruit. For no-cook days substitute a ready-to-eat labeled breakfast.');
 meal(day,`d${day}-lunch`,day%2?'Tuna and chickpea vegetable bowl':'Chicken, black bean and corn bowl','No-cook',day%2?[['c-tuna',0.5],['c-chickpeas',0.4],['c-carrots',0.25]]:[['c-chicken',0.3],['c-black-beans',0.4],['c-corn',0.25]],0,0,'Use commercially canned food labeled ready to eat. Drain as desired, mix and serve promptly. Scale for the group to use whole packages.');
 meal(day,`d${day}-dinner`,day%3===0?'Pasta, tomato and chicken':day%2?'Rice, beans and canned chicken':'Canned chili with rice','Cooked',day%3===0?[['pasta',0.2],['c-tomato-sauce',0.35],['c-chicken',0.3],['oil',0.004]]:day%2?[['white-rice',0.2],['c-kidney-beans',0.4],['c-chicken',0.3],['oil',0.004]]:[['white-rice',0.18],['c-chili',0.6],['c-green-beans',0.3]],0.5,25,'Cook rice or pasta by its package directions on a safe outdoor stove. Add the canned ingredients and heat as directed. Prepare only the amount the group will eat.');
}
const content={version:verified,sources,products,storage,recipes,guides,meals,equipment};
if(products.filter(p=>p.kind==='COMMERCIAL').reduce((n,p)=>n+p.target,0)!==324) throw Error('Starter canned target must be 324');
const known=new Set(sources.map(s=>s.id));
for(const entry of [...storage,...recipes,...guides]) for(const id of entry.sources) if(!known.has(id)) throw Error('Missing source '+id);
mkdirSync(new URL('../app/src/main/assets/food/',import.meta.url),{recursive:true});
writeFileSync(new URL('../app/src/main/assets/food/content.json',import.meta.url),JSON.stringify(content,null,2)+'\n');
console.log(`${products.length} products, ${recipes.filter(r=>r.verified).length} verified preparation records, ${guides.length} guides, ${meals.length} meal examples`);
