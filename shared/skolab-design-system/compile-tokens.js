const fs = require('fs');
const path = require('path');

// Paths
const tokensPath = path.join(__dirname, 'design_tokens.json');
const colorKtPath = path.join(__dirname, '..', '..', 'apps', 'android-app', 'app', 'src', 'main', 'java', 'com', 'company', 'skolab', 'ui', 'theme', 'Color.kt');

try {
  // Read design_tokens.json
  const tokens = JSON.parse(fs.readFileSync(tokensPath, 'utf8'));
  const colors = tokens.colors;

  // Generate Compose definitions
  let generatedCode = '';

  for (const [key, themeValues] of Object.entries(colors)) {
    const composeKey = key.toUpperCase();
    const lightHex = themeValues.light.replace('#', '').toUpperCase();
    const darkHex = themeValues.dark.replace('#', '').toUpperCase();
    
    generatedCode += `val ${composeKey}: Color\n`;
    generatedCode += `    get() = if (isDarkThemeActive()) Color(0xFF${darkHex}) else Color(0xFF${lightHex})\n\n`;
  }

  // Remove the trailing newline
  generatedCode = generatedCode.trim();

  // Read Color.kt
  let colorKtContent = fs.readFileSync(colorKtPath, 'utf8');

  // Replace block
  const startMarker = '// <GENERATED_TOKENS>';
  const endMarker = '// </GENERATED_TOKENS>';

  const startIndex = colorKtContent.indexOf(startMarker);
  const endIndex = colorKtContent.indexOf(endMarker);

  if (startIndex === -1 || endIndex === -1) {
    console.error("Markers not found in Color.kt!");
    process.exit(1);
  }

  const beforeBlock = colorKtContent.substring(0, startIndex + startMarker.length);
  const afterBlock = colorKtContent.substring(endIndex);

  const newContent = `${beforeBlock}\n${generatedCode}\n${afterBlock}`;

  fs.writeFileSync(colorKtPath, newContent, 'utf8');
  console.log('Successfully compiled tokens to Color.kt!');
} catch (err) {
  console.error('Failed to compile design tokens:', err);
  process.exit(1);
}
