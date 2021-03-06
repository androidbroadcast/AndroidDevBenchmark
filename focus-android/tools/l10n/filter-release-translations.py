# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""
The following script was invoked for release builds in CI and used to remove strings.xml files for
locales that were not defined in a release locales list.
With #5474 we change this behavior: Now we ship all translations without explicitly approving them.
This script is still called in automation and we keep it in case we will have to explicitly remove
a locale from our releases.
"""

print("Filtering of release locales disabled (#5474)")

"""
import os
import re
import shutil

LANGUAGE_REGEX = re.compile('^[a-z]{2,3}$')
LANGUAGE_REGION_REGEX = re.compile('^([a-z]{2})-r([A-Z]{2})$')

# Get all resource directories that start with "values-" (and remove the "values-" prefix)
resources_dir = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'res')
locale_dirs = [localeDir.replace('values-', '') for localeDir in os.listdir(resources_dir)
		if os.path.isdir(os.path.join(resources_dir, localeDir))
		and localeDir.startswith('values-')]

# Remove everything that doesn't look like it's a resource folder for a specific language (e.g. sw600dp)
locale_dirs = filter(lambda x: LANGUAGE_REGEX.match(x) or LANGUAGE_REGION_REGEX.match(x), locale_dirs)

# Android prefixes regions with an "r" (de-rDE). Remove the prefix.
locale_dirs = [item.replace('-r','-') for item in locale_dirs]

# Now determine the list of locales that are not in our release list
locales_to_remove = list(set(locale_dirs) - set(RELEASE_LOCALES))

print "RELEASE LOCALES:", ", ".join(RELEASE_LOCALES)
print "APP LOCALES:", ", ".join(locale_dirs)
print "REMOVE:", ", ".join(locales_to_remove) if len(locales_to_remove) > 0 else "-Nothing-"

# Remove unneeded resource folders
for locale in locales_to_remove:
	# Build resource folder names from locale: de -> values-de, de-DE -> vlaues-de-rDE
	parts = locale.split("-")
	folder = "values-" + (parts[0] if len(parts) == 1 else parts[0] + "-r" + parts[1])
	path = os.path.join(resources_dir, folder)
	
	print "* Removing: ", path
	shutil.rmtree(path)
"""

