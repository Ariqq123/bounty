cat << 'PATCH_EOF' > /tmp/gradle.patch
--- build.gradle.kts
+++ build.gradle.kts
@@ -15,6 +15,8 @@
     testImplementation("com.github.MilkBowl:VaultAPI:1.7.1")
 
     implementation("org.xerial:sqlite-jdbc:3.49.1.0")
+    implementation("com.zaxxer:HikariCP:5.1.0")
+    implementation("com.mysql:mysql-connector-j:8.3.0")
 
     testImplementation(platform("org.junit:junit-bom:5.12.2"))
     testImplementation("org.junit.jupiter:junit-jupiter")
PATCH_EOF
patch build.gradle.kts /tmp/gradle.patch
