/**
 * @author
 * AnkiDroid Open Source Team
 *
 * @license
 * Copyright (c) AnkiDroid. All rights reserved.
 * Licensed under the GPL-3.0 license. See LICENSE file in the project root for details.
 */

import path from "path";
import { Credentials } from "@crowdin/crowdin-api-client";
import { createDirIfNotExisting } from "./update";

import dotenv from "dotenv";
dotenv.config({ path: path.join(__dirname, "../.env") });

const CROWDIN_APIv2_PAT = process.env.CROWDIN_APIv2_PAT ?? "";

// credentials
export const credentialsConst: Credentials = {
    token: CROWDIN_APIv2_PAT,
};

export const PROJECT_ID = 720;
export const TITLE_STR = "AnkiDroid Flashcards";

const RES_DIR = "../../../AnkiDroid/src/main/res";
const DOCS_MARKET_DIR = "../../../docs/marketing/localized_description";

export const TEMP_DIR = path.join(__dirname, "../temp_dir");
createDirIfNotExisting(TEMP_DIR);

export const I18N_FILES_DIR = path.join(__dirname, RES_DIR, "values/");
export const RES_VALUES_LANG_DIR = path.join(__dirname, RES_DIR, "values-");
export const MARKET_DESC_FILE = path.join(
    __dirname,
    DOCS_MARKET_DIR,
    "marketdescription.txt",
);
export const OLD_VER_MARKET_DESC_FILE = path.join(
    __dirname,
    DOCS_MARKET_DIR,
    "oldVersionJustToCompareWith.txt",
);
export const MARKET_DESC_LANG = path.join(
    __dirname,
    DOCS_MARKET_DIR,
    "marketdescription-",
);

export const I18N_FILES = [
    "01-core",
    "02-strings",
    "03-dialogs",
    "04-network",
    "05-feedback",
    "06-statistics",
    "07-cardbrowser",
    "08-widget",
    "09-backup",
    "10-preferences",
    "11-arrays",
    "12-dont-translate",
    "14-marketdescription",
    "16-multimedia-editor",
    "17-model-manager",
    "18-standard-models",
    "20-search-preference",
];

// Below is the list of official AnkiDroid localizations.
//
// The rules for making changes here:
// 1) Add a language if 01-core.xml is translated
// 2) Do not remove languages.
// 3) When you add a language, please also add it to APP_LANGUAGES in LanguageUtil.kt
//    BACKEND_LANGS in LanguageUtil is informational and not used for anything, so it's not imperative
//    to keep it up to date.
// 4) If you add a language with a regional variant (anything with a hyphen) and a different variant
//    with the same root exists, you must add the root to 'localizedRegions'
//    e.g., 'ga-IE' exists with no other 'ga-' entries yet, to add 'ga-EN', also add ga to localizedRegions
// 5) Update MissingDefaultResource in lint-release.xml
export const LANGUAGES = [
    "af",
    "am",
    "ar",
    "az",
    "be",
    "bg",
    "bn",
    "ca",
    "ckb",
    "cs",
    "da",
    "de",
    "el",
    "en-GB",
    "eo",
    "es-AR",
    "es-ES",
    "et",
    "eu",
    "fa",
    "fi",
    "fil",
    "fr",
    "fy-NL",
    "ga-IE",
    "gl",
    "got",
    "gu-IN",
    "he",
    "hi",
    "hr",
    "hu",
    "hy-AM",
    "id",
    "is",
    "it",
    "ja",
    "jv",
    "ka",
    "kk",
    "km",
    "kn",
    "ko",
    "ku",
    "ky",
    "lt",
    "lv",
    "mk",
    "ml-IN",
    "mn",
    "mr",
    "ms",
    "my",
    "nl",
    "nn-NO",
    "no",
    "or",
    "pa-IN",
    "pl",
    "pt-BR",
    "pt-PT",
    "ro",
    "ru",
    "sat",
    "sc",
    "sk",
    "sl",
    "sq",
    "sr",
    "ss",
    "sv-SE",
    "sw",
    "ta",
    "te",
    "tg",
    "th",
    "ti",
    "tl",
    "tn",
    "tr",
    "ts",
    "tt-RU",
    "uk",
    "ur-PK",
    "uz",
    "ve",
    "vi",
    "wo",
    "xh",
    "yu",
    "zh-CN",
    "zh-TW",
    "zu",
];

// languages which are localized for more than one region
export const LOCALIZED_REGIONS = ["en", "es", "pt", "zh"];

export const XML_LICENSE_HEADER = `<?xml version="1.0" encoding="utf-8"?> 
 <!--
 ~ THIS IS AN AUTOMATICALLY GENERATED FILE. PLEASE DO NOT EDIT THIS FILE. 
 ~ 1. If you would like to add/delete/modify the original translatable strings, follow instructions here:  https://github.com/ankidroid/Anki-Android/wiki/Development-Guide#adding-translations  
 ~ 2. If you would like to provide a translation of the original file, you may do so using Crowdin. 
 ~    Instructions for this are available here: https://github.com/ankidroid/Anki-Android/wiki/Translating-AnkiDroid. 
 ~    You may also find the documentation on contributing to Anki useful: https://github.com/ankidroid/Anki-Android/wiki/Contributing   
 ~ 
 ~ Copyright (c) 2009 Andrew <andrewdubya@gmail> 
 ~ Copyright (c) 2009 Edu Zamora <edu.zasu@gmail.com> 
 ~ Copyright (c) 2009 Daniel Svaerd <daniel.svard@gmail.com> 
 ~ Copyright (c) 2009 Nicolas Raoul <nicolas.raoul@gmail.com> 
 ~ Copyright (c) 2010 Norbert Nagold <norbert.nagold@gmail.com> 
 ~ This program is free software; you can redistribute it and/or modify it under 
 ~ the terms of the GNU General Public License as published by the Free Software 
 ~ Foundation; either version 3 of the License, or (at your option) any later 
 ~ version. 
 ~ 
 ~ This program is distributed in the hope that it will be useful, but WITHOUT ANY 
 ~ WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 ~ PARTICULAR PURPOSE. See the GNU General Public License for more details. 
 ~ 
 ~ You should have received a copy of the GNU General Public License along with 
 ~ this program.  If not, see <http://www.gnu.org/licenses/>. 
 -->
 `;
