# JDBC 驱动目录

将数据库 JDBC 驱动 JAR 文件放在此目录下。

## 推荐随 skill 一起提供的驱动

| 文件名 | 数据库 | 许可 | 说明 |
|--------|--------|------|------|
| `mysql-connector-j-*.jar` | MySQL | GPL v2 | 建议随 skill 提供，降低使用门槛 |
| `postgresql-*.jar` | PostgreSQL | BSD | 建议随 skill 提供，降低使用门槛 |

## 不建议随 skill 分发的驱动

| 文件名 | 数据库 | 许可 / 来源 | 获取方式 |
|--------|--------|-------------|---------|
| `ojdbc8.jar` | Oracle | Oracle OTN | Oracle 官网下载或公司统一共享目录获取 |
| `DmJdbcDriver.jar` / `DmJdbcDriver18.jar` | 达梦 | 达梦安装介质 / 商业驱动 | 从达梦安装目录 `drivers/jdbc/` 拷贝，或公司统一共享目录获取 |

## 发布建议

- 公司贡献库版本建议：
  - **可包含**：MySQL、PostgreSQL 驱动
  - **不包含**：Oracle、达梦驱动
- Oracle / 达梦如需统一使用，建议在公司网盘或共享目录提供下载位置，由使用者自行放入本目录。

## 注意事项

- JAR 文件名支持通配符匹配，例如：`mysql-connector-j-*.jar`
- 不需要解压，直接放 JAR 文件即可
- 如果只使用某一种数据库，只保留对应驱动即可
