# Shop Scraper (Fabric, Minecraft 26.1.2, client-side)

1. Open EconomyShopGUI menus yourself. Buy/Sell lore lines are recorded; closing the menu saves `shop_prices.csv` in your game folder.
2. Run `/shopflips [count]` for the best flips. Full list goes to `shop_flips.csv`.
   - BUY->SELL: shop pays more than it charges.
   - CRAFT: cheapest ingredient buy prices vs the result's sell price (vanilla shaped/shapeless recipes).
   Note: recipes need ingredient *buy* prices and the result's *sell* price to be scanned.

## Build
- Needs JDK 25: `./gradlew build` -> `build/libs/shopscraper-1.1.0.jar`
- No JDK? Push this folder to a GitHub repo; the Actions workflow builds the jar (Actions tab -> artifact "shopscraper").
