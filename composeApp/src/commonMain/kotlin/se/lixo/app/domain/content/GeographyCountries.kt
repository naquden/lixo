package se.lixo.app.domain.content

import org.jetbrains.compose.resources.DrawableResource
import lixo.composeapp.generated.resources.*

/**
 * The 7 flag regions used by the geography world (plan 037), shown in flag hints and level titles.
 */
enum class FlagRegion(val swedishName: String) {
    NORDEN("Norden"),
    EUROPA("Europa"),
    ASIEN("Asien"),
    AFRIKA("Afrika"),
    NORDAMERIKA("Nordamerika"),
    SYDAMERIKA("Sydamerika"),
    OCEANIEN("Oceanien")
}

/**
 * One of the 194 countries (193 UN member states + Vatikanstaten) covered by the "Världsresan"
 * geography world (plan 037). Territories (Grönland, Färöarna, Åland) and partially recognised
 * states (Kosovo, Taiwan, Palestina) are deliberately excluded. [iso2] is always lowercase.
 * [flag] is the typed `Res.drawable.flag_<iso2>` compose resource, so a missing bundled flag
 * file is a compile error rather than a runtime lookup failure (plan 038).
 */
data class Country(
    val iso2: String,
    val nameSv: String,
    val region: FlagRegion,
    val colorsSv: List<String>,
    val isNordic: Boolean = false,
    val flag: DrawableResource
)

/**
 * Static country/flag data for the geography world (plan 037). [RANKED] orders all 194 countries
 * from a Swedish child's perspective: Nordic countries first, then world-famous/large countries,
 * then the rest, roughly by continent. [GeographyCurriculum] slices [RANKED] into 30 levels in
 * this exact order, so the order here is persisted indirectly via `FlagQuestion.countryIso2` /
 * `correctAnswer` values baked into the seeded curriculum - never reorder existing entries.
 */
object GeographyCountries {
    /** The only flag colour words hint text and [Country.colorsSv] may use. */
    val COLOR_VOCAB: List<String> = listOf("röd", "vit", "blå", "ljusblå", "gul", "grön", "svart", "orange")

    val RANKED: List<Country> = listOf(
    Country("se", "Sverige", FlagRegion.NORDEN, listOf("blå", "gul"), isNordic = true, flag = Res.drawable.flag_se),
    Country("no", "Norge", FlagRegion.NORDEN, listOf("röd", "vit", "blå"), isNordic = true, flag = Res.drawable.flag_no),
    Country("dk", "Danmark", FlagRegion.NORDEN, listOf("röd", "vit"), isNordic = true, flag = Res.drawable.flag_dk),
    Country("fi", "Finland", FlagRegion.NORDEN, listOf("vit", "blå"), isNordic = true, flag = Res.drawable.flag_fi),
    Country("is", "Island", FlagRegion.NORDEN, listOf("blå", "vit", "röd"), isNordic = true, flag = Res.drawable.flag_is),
    Country("us", "USA", FlagRegion.NORDAMERIKA, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_us),
    Country("gb", "Storbritannien", FlagRegion.EUROPA, listOf("blå", "röd", "vit"), flag = Res.drawable.flag_gb),
    Country("de", "Tyskland", FlagRegion.EUROPA, listOf("svart", "röd", "gul"), flag = Res.drawable.flag_de),
    Country("fr", "Frankrike", FlagRegion.EUROPA, listOf("blå", "vit", "röd"), flag = Res.drawable.flag_fr),
    Country("it", "Italien", FlagRegion.EUROPA, listOf("grön", "vit", "röd"), flag = Res.drawable.flag_it),
    Country("es", "Spanien", FlagRegion.EUROPA, listOf("röd", "gul"), flag = Res.drawable.flag_es),
    Country("jp", "Japan", FlagRegion.ASIEN, listOf("vit", "röd"), flag = Res.drawable.flag_jp),
    Country("cn", "Kina", FlagRegion.ASIEN, listOf("röd", "gul"), flag = Res.drawable.flag_cn),
    Country("ca", "Kanada", FlagRegion.NORDAMERIKA, listOf("röd", "vit"), flag = Res.drawable.flag_ca),
    Country("br", "Brasilien", FlagRegion.SYDAMERIKA, listOf("grön", "gul", "blå", "vit"), flag = Res.drawable.flag_br),
    Country("au", "Australien", FlagRegion.OCEANIEN, listOf("blå", "röd", "vit"), flag = Res.drawable.flag_au),
    Country("ru", "Ryssland", FlagRegion.EUROPA, listOf("vit", "blå", "röd"), flag = Res.drawable.flag_ru),
    Country("in", "Indien", FlagRegion.ASIEN, listOf("orange", "vit", "grön", "blå"), flag = Res.drawable.flag_in),
    Country("mx", "Mexiko", FlagRegion.NORDAMERIKA, listOf("grön", "vit", "röd"), flag = Res.drawable.flag_mx),
    Country("nl", "Nederländerna", FlagRegion.EUROPA, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_nl),
    Country("gr", "Grekland", FlagRegion.EUROPA, listOf("blå", "vit"), flag = Res.drawable.flag_gr),
    Country("pt", "Portugal", FlagRegion.EUROPA, listOf("grön", "röd", "gul"), flag = Res.drawable.flag_pt),
    Country("ch", "Schweiz", FlagRegion.EUROPA, listOf("röd", "vit"), flag = Res.drawable.flag_ch),
    Country("at", "Österrike", FlagRegion.EUROPA, listOf("röd", "vit"), flag = Res.drawable.flag_at),
    Country("pl", "Polen", FlagRegion.EUROPA, listOf("vit", "röd"), flag = Res.drawable.flag_pl),
    Country("tr", "Turkiet", FlagRegion.EUROPA, listOf("röd", "vit"), flag = Res.drawable.flag_tr),
    Country("eg", "Egypten", FlagRegion.AFRIKA, listOf("röd", "vit", "svart"), flag = Res.drawable.flag_eg),
    Country("be", "Belgien", FlagRegion.EUROPA, listOf("svart", "gul", "röd"), flag = Res.drawable.flag_be),
    Country("ie", "Irland", FlagRegion.EUROPA, listOf("grön", "vit", "orange"), flag = Res.drawable.flag_ie),
    Country("ua", "Ukraina", FlagRegion.EUROPA, listOf("blå", "gul"), flag = Res.drawable.flag_ua),
    Country("ee", "Estland", FlagRegion.EUROPA, listOf("blå", "svart", "vit"), flag = Res.drawable.flag_ee),
    Country("ar", "Argentina", FlagRegion.SYDAMERIKA, listOf("ljusblå", "vit", "gul"), flag = Res.drawable.flag_ar),
    Country("za", "Sydafrika", FlagRegion.AFRIKA, listOf("svart", "grön", "gul", "röd"), flag = Res.drawable.flag_za),
    Country("kr", "Sydkorea", FlagRegion.ASIEN, listOf("vit", "röd", "blå", "svart"), flag = Res.drawable.flag_kr),
    Country("th", "Thailand", FlagRegion.ASIEN, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_th),
    Country("nz", "Nya Zeeland", FlagRegion.OCEANIEN, listOf("blå", "röd", "vit"), flag = Res.drawable.flag_nz),
    Country("hr", "Kroatien", FlagRegion.EUROPA, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_hr),
    Country("lv", "Lettland", FlagRegion.EUROPA, listOf("röd", "vit"), flag = Res.drawable.flag_lv),
    Country("lt", "Litauen", FlagRegion.EUROPA, listOf("gul", "grön", "röd"), flag = Res.drawable.flag_lt),
    Country("cz", "Tjeckien", FlagRegion.EUROPA, listOf("vit", "röd", "blå"), flag = Res.drawable.flag_cz),
    Country("hu", "Ungern", FlagRegion.EUROPA, listOf("röd", "vit", "grön"), flag = Res.drawable.flag_hu),
    Country("il", "Israel", FlagRegion.ASIEN, listOf("vit", "blå"), flag = Res.drawable.flag_il),
    Country("jm", "Jamaica", FlagRegion.NORDAMERIKA, listOf("grön", "svart", "gul"), flag = Res.drawable.flag_jm),
    Country("cu", "Kuba", FlagRegion.NORDAMERIKA, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_cu),
    Country("ma", "Marocko", FlagRegion.AFRIKA, listOf("röd", "grön"), flag = Res.drawable.flag_ma),
    Country("sa", "Saudiarabien", FlagRegion.ASIEN, listOf("grön", "vit"), flag = Res.drawable.flag_sa),
    Country("id", "Indonesien", FlagRegion.ASIEN, listOf("röd", "vit"), flag = Res.drawable.flag_id),
    Country("vn", "Vietnam", FlagRegion.ASIEN, listOf("röd", "gul"), flag = Res.drawable.flag_vn),
    Country("cl", "Chile", FlagRegion.SYDAMERIKA, listOf("vit", "röd", "blå"), flag = Res.drawable.flag_cl),
    Country("co", "Colombia", FlagRegion.SYDAMERIKA, listOf("gul", "blå", "röd"), flag = Res.drawable.flag_co),
    Country("ke", "Kenya", FlagRegion.AFRIKA, listOf("svart", "röd", "grön", "vit"), flag = Res.drawable.flag_ke),
    Country("ng", "Nigeria", FlagRegion.AFRIKA, listOf("grön", "vit"), flag = Res.drawable.flag_ng),
    Country("pe", "Peru", FlagRegion.SYDAMERIKA, listOf("röd", "vit"), flag = Res.drawable.flag_pe),
    Country("ph", "Filippinerna", FlagRegion.ASIEN, listOf("blå", "röd", "vit", "gul"), flag = Res.drawable.flag_ph),
    Country("ae", "Förenade Arabemiraten", FlagRegion.ASIEN, listOf("röd", "grön", "vit", "svart"), flag = Res.drawable.flag_ae),
    Country("kp", "Nordkorea", FlagRegion.ASIEN, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_kp),
    Country("ir", "Iran", FlagRegion.ASIEN, listOf("grön", "vit", "röd"), flag = Res.drawable.flag_ir),
    Country("iq", "Irak", FlagRegion.ASIEN, listOf("röd", "vit", "svart"), flag = Res.drawable.flag_iq),
    Country("pk", "Pakistan", FlagRegion.ASIEN, listOf("grön", "vit"), flag = Res.drawable.flag_pk),
    Country("sg", "Singapore", FlagRegion.ASIEN, listOf("röd", "vit"), flag = Res.drawable.flag_sg),
    Country("my", "Malaysia", FlagRegion.ASIEN, listOf("blå", "röd", "vit", "gul"), flag = Res.drawable.flag_my),
    Country("ro", "Rumänien", FlagRegion.EUROPA, listOf("blå", "gul", "röd"), flag = Res.drawable.flag_ro),
    Country("bg", "Bulgarien", FlagRegion.EUROPA, listOf("vit", "grön", "röd"), flag = Res.drawable.flag_bg),
    Country("rs", "Serbien", FlagRegion.EUROPA, listOf("röd", "blå", "vit"), flag = Res.drawable.flag_rs),
    Country("sk", "Slovakien", FlagRegion.EUROPA, listOf("vit", "blå", "röd"), flag = Res.drawable.flag_sk),
    Country("si", "Slovenien", FlagRegion.EUROPA, listOf("vit", "blå", "röd"), flag = Res.drawable.flag_si),
    Country("lu", "Luxemburg", FlagRegion.EUROPA, listOf("röd", "vit", "ljusblå"), flag = Res.drawable.flag_lu),
    Country("mt", "Malta", FlagRegion.EUROPA, listOf("vit", "röd"), flag = Res.drawable.flag_mt),
    Country("cy", "Cypern", FlagRegion.EUROPA, listOf("vit", "orange"), flag = Res.drawable.flag_cy),
    Country("mc", "Monaco", FlagRegion.EUROPA, listOf("röd", "vit"), flag = Res.drawable.flag_mc),
    Country("va", "Vatikanstaten", FlagRegion.EUROPA, listOf("gul", "vit"), flag = Res.drawable.flag_va),
    Country("li", "Liechtenstein", FlagRegion.EUROPA, listOf("blå", "röd", "gul"), flag = Res.drawable.flag_li),
    Country("ad", "Andorra", FlagRegion.EUROPA, listOf("blå", "gul", "röd"), flag = Res.drawable.flag_ad),
    Country("sm", "San Marino", FlagRegion.EUROPA, listOf("vit", "ljusblå"), flag = Res.drawable.flag_sm),
    Country("ba", "Bosnien och Hercegovina", FlagRegion.EUROPA, listOf("blå", "gul", "vit"), flag = Res.drawable.flag_ba),
    Country("al", "Albanien", FlagRegion.EUROPA, listOf("röd", "svart"), flag = Res.drawable.flag_al),
    Country("mk", "Nordmakedonien", FlagRegion.EUROPA, listOf("röd", "gul"), flag = Res.drawable.flag_mk),
    Country("me", "Montenegro", FlagRegion.EUROPA, listOf("röd", "gul"), flag = Res.drawable.flag_me),
    Country("md", "Moldavien", FlagRegion.EUROPA, listOf("blå", "gul", "röd"), flag = Res.drawable.flag_md),
    Country("by", "Belarus", FlagRegion.EUROPA, listOf("röd", "grön"), flag = Res.drawable.flag_by),
    Country("ge", "Georgien", FlagRegion.EUROPA, listOf("vit", "röd"), flag = Res.drawable.flag_ge),
    Country("ve", "Venezuela", FlagRegion.SYDAMERIKA, listOf("gul", "blå", "röd"), flag = Res.drawable.flag_ve),
    Country("uy", "Uruguay", FlagRegion.SYDAMERIKA, listOf("vit", "ljusblå", "gul"), flag = Res.drawable.flag_uy),
    Country("ec", "Ecuador", FlagRegion.SYDAMERIKA, listOf("gul", "blå", "röd"), flag = Res.drawable.flag_ec),
    Country("bo", "Bolivia", FlagRegion.SYDAMERIKA, listOf("röd", "gul", "grön"), flag = Res.drawable.flag_bo),
    Country("py", "Paraguay", FlagRegion.SYDAMERIKA, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_py),
    Country("et", "Etiopien", FlagRegion.AFRIKA, listOf("grön", "gul", "röd"), flag = Res.drawable.flag_et),
    Country("tn", "Tunisien", FlagRegion.AFRIKA, listOf("röd", "vit"), flag = Res.drawable.flag_tn),
    Country("dz", "Algeriet", FlagRegion.AFRIKA, listOf("grön", "vit", "röd"), flag = Res.drawable.flag_dz),
    Country("qa", "Qatar", FlagRegion.ASIEN, listOf("röd", "vit"), flag = Res.drawable.flag_qa),
    Country("mn", "Mongoliet", FlagRegion.ASIEN, listOf("röd", "blå", "gul"), flag = Res.drawable.flag_mn),
    Country("af", "Afghanistan", FlagRegion.ASIEN, listOf("svart", "röd", "grön"), flag = Res.drawable.flag_af),
    Country("np", "Nepal", FlagRegion.ASIEN, listOf("röd", "blå", "vit"), flag = Res.drawable.flag_np),
    Country("tz", "Tanzania", FlagRegion.AFRIKA, listOf("grön", "gul", "svart", "blå"), flag = Res.drawable.flag_tz),
    Country("gh", "Ghana", FlagRegion.AFRIKA, listOf("röd", "gul", "grön", "svart"), flag = Res.drawable.flag_gh),
    Country("sn", "Senegal", FlagRegion.AFRIKA, listOf("grön", "gul", "röd"), flag = Res.drawable.flag_sn),
    Country("mg", "Madagaskar", FlagRegion.AFRIKA, listOf("vit", "röd", "grön"), flag = Res.drawable.flag_mg),
    Country("sy", "Syrien", FlagRegion.ASIEN, listOf("röd", "vit", "svart", "grön"), flag = Res.drawable.flag_sy),
    Country("jo", "Jordanien", FlagRegion.ASIEN, listOf("svart", "vit", "grön", "röd"), flag = Res.drawable.flag_jo),
    Country("lb", "Libanon", FlagRegion.ASIEN, listOf("röd", "vit", "grön"), flag = Res.drawable.flag_lb),
    Country("kz", "Kazakstan", FlagRegion.ASIEN, listOf("ljusblå", "gul"), flag = Res.drawable.flag_kz),
    Country("bd", "Bangladesh", FlagRegion.ASIEN, listOf("grön", "röd"), flag = Res.drawable.flag_bd),
    Country("lk", "Sri Lanka", FlagRegion.ASIEN, listOf("gul", "röd", "grön", "orange"), flag = Res.drawable.flag_lk),
    Country("mm", "Myanmar", FlagRegion.ASIEN, listOf("gul", "grön", "röd"), flag = Res.drawable.flag_mm),
    Country("kh", "Kambodja", FlagRegion.ASIEN, listOf("blå", "röd", "vit"), flag = Res.drawable.flag_kh),
    Country("la", "Laos", FlagRegion.ASIEN, listOf("röd", "blå", "vit"), flag = Res.drawable.flag_la),
    Country("uz", "Uzbekistan", FlagRegion.ASIEN, listOf("blå", "vit", "grön"), flag = Res.drawable.flag_uz),
    Country("am", "Armenien", FlagRegion.EUROPA, listOf("röd", "blå", "orange"), flag = Res.drawable.flag_am),
    Country("az", "Azerbajdzjan", FlagRegion.EUROPA, listOf("blå", "röd", "grön"), flag = Res.drawable.flag_az),
    Country("kw", "Kuwait", FlagRegion.ASIEN, listOf("grön", "vit", "röd", "svart"), flag = Res.drawable.flag_kw),
    Country("om", "Oman", FlagRegion.ASIEN, listOf("röd", "vit", "grön"), flag = Res.drawable.flag_om),
    Country("ye", "Jemen", FlagRegion.ASIEN, listOf("röd", "vit", "svart"), flag = Res.drawable.flag_ye),
    Country("bh", "Bahrain", FlagRegion.ASIEN, listOf("röd", "vit"), flag = Res.drawable.flag_bh),
    Country("ly", "Libyen", FlagRegion.AFRIKA, listOf("röd", "svart", "grön", "vit"), flag = Res.drawable.flag_ly),
    Country("sd", "Sudan", FlagRegion.AFRIKA, listOf("röd", "vit", "svart", "grön"), flag = Res.drawable.flag_sd),
    Country("so", "Somalia", FlagRegion.AFRIKA, listOf("ljusblå", "vit"), flag = Res.drawable.flag_so),
    Country("cd", "Kongo-Kinshasa", FlagRegion.AFRIKA, listOf("blå", "röd", "gul"), flag = Res.drawable.flag_cd),
    Country("ug", "Uganda", FlagRegion.AFRIKA, listOf("svart", "gul", "röd"), flag = Res.drawable.flag_ug),
    Country("rw", "Rwanda", FlagRegion.AFRIKA, listOf("blå", "gul", "grön"), flag = Res.drawable.flag_rw),
    Country("cm", "Kamerun", FlagRegion.AFRIKA, listOf("grön", "röd", "gul"), flag = Res.drawable.flag_cm),
    Country("ci", "Elfenbenskusten", FlagRegion.AFRIKA, listOf("orange", "vit", "grön"), flag = Res.drawable.flag_ci),
    Country("ao", "Angola", FlagRegion.AFRIKA, listOf("röd", "svart", "gul"), flag = Res.drawable.flag_ao),
    Country("mz", "Moçambique", FlagRegion.AFRIKA, listOf("grön", "svart", "gul", "röd"), flag = Res.drawable.flag_mz),
    Country("zw", "Zimbabwe", FlagRegion.AFRIKA, listOf("grön", "gul", "röd", "svart"), flag = Res.drawable.flag_zw),
    Country("zm", "Zambia", FlagRegion.AFRIKA, listOf("grön", "röd", "svart", "orange"), flag = Res.drawable.flag_zm),
    Country("na", "Namibia", FlagRegion.AFRIKA, listOf("blå", "röd", "grön", "vit"), flag = Res.drawable.flag_na),
    Country("bw", "Botswana", FlagRegion.AFRIKA, listOf("blå", "vit", "svart"), flag = Res.drawable.flag_bw),
    Country("cr", "Costa Rica", FlagRegion.NORDAMERIKA, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_cr),
    Country("pa", "Panama", FlagRegion.NORDAMERIKA, listOf("vit", "blå", "röd"), flag = Res.drawable.flag_pa),
    Country("do", "Dominikanska republiken", FlagRegion.NORDAMERIKA, listOf("blå", "röd", "vit"), flag = Res.drawable.flag_do),
    Country("ht", "Haiti", FlagRegion.NORDAMERIKA, listOf("blå", "röd"), flag = Res.drawable.flag_ht),
    Country("gt", "Guatemala", FlagRegion.NORDAMERIKA, listOf("ljusblå", "vit"), flag = Res.drawable.flag_gt),
    Country("hn", "Honduras", FlagRegion.NORDAMERIKA, listOf("ljusblå", "vit"), flag = Res.drawable.flag_hn),
    Country("sv", "El Salvador", FlagRegion.NORDAMERIKA, listOf("ljusblå", "vit"), flag = Res.drawable.flag_sv),
    Country("ni", "Nicaragua", FlagRegion.NORDAMERIKA, listOf("ljusblå", "vit"), flag = Res.drawable.flag_ni),
    Country("bs", "Bahamas", FlagRegion.NORDAMERIKA, listOf("ljusblå", "gul", "svart"), flag = Res.drawable.flag_bs),
    Country("bb", "Barbados", FlagRegion.NORDAMERIKA, listOf("blå", "gul", "svart"), flag = Res.drawable.flag_bb),
    Country("tt", "Trinidad och Tobago", FlagRegion.NORDAMERIKA, listOf("röd", "vit", "svart"), flag = Res.drawable.flag_tt),
    Country("fj", "Fiji", FlagRegion.OCEANIEN, listOf("ljusblå", "vit", "röd"), flag = Res.drawable.flag_fj),
    Country("pg", "Papua Nya Guinea", FlagRegion.OCEANIEN, listOf("röd", "svart", "gul"), flag = Res.drawable.flag_pg),
    Country("mv", "Maldiverna", FlagRegion.ASIEN, listOf("röd", "grön", "vit"), flag = Res.drawable.flag_mv),
    Country("bt", "Bhutan", FlagRegion.ASIEN, listOf("gul", "orange", "vit"), flag = Res.drawable.flag_bt),
    Country("kg", "Kirgizistan", FlagRegion.ASIEN, listOf("röd", "gul"), flag = Res.drawable.flag_kg),
    Country("tj", "Tadzjikistan", FlagRegion.ASIEN, listOf("röd", "vit", "grön"), flag = Res.drawable.flag_tj),
    Country("tm", "Turkmenistan", FlagRegion.ASIEN, listOf("grön", "röd", "vit"), flag = Res.drawable.flag_tm),
    Country("bn", "Brunei", FlagRegion.ASIEN, listOf("gul", "vit", "svart", "röd"), flag = Res.drawable.flag_bn),
    Country("tl", "Östtimor", FlagRegion.ASIEN, listOf("röd", "gul", "svart", "vit"), flag = Res.drawable.flag_tl),
    Country("ml", "Mali", FlagRegion.AFRIKA, listOf("grön", "gul", "röd"), flag = Res.drawable.flag_ml),
    Country("ne", "Niger", FlagRegion.AFRIKA, listOf("orange", "vit", "grön"), flag = Res.drawable.flag_ne),
    Country("td", "Tchad", FlagRegion.AFRIKA, listOf("blå", "gul", "röd"), flag = Res.drawable.flag_td),
    Country("bf", "Burkina Faso", FlagRegion.AFRIKA, listOf("röd", "grön", "gul"), flag = Res.drawable.flag_bf),
    Country("bj", "Benin", FlagRegion.AFRIKA, listOf("grön", "gul", "röd"), flag = Res.drawable.flag_bj),
    Country("tg", "Togo", FlagRegion.AFRIKA, listOf("grön", "gul", "röd", "vit"), flag = Res.drawable.flag_tg),
    Country("gn", "Guinea", FlagRegion.AFRIKA, listOf("röd", "gul", "grön"), flag = Res.drawable.flag_gn),
    Country("gw", "Guinea-Bissau", FlagRegion.AFRIKA, listOf("röd", "gul", "grön", "svart"), flag = Res.drawable.flag_gw),
    Country("sl", "Sierra Leone", FlagRegion.AFRIKA, listOf("grön", "vit", "blå"), flag = Res.drawable.flag_sl),
    Country("lr", "Liberia", FlagRegion.AFRIKA, listOf("röd", "vit", "blå"), flag = Res.drawable.flag_lr),
    Country("gm", "Gambia", FlagRegion.AFRIKA, listOf("röd", "blå", "grön"), flag = Res.drawable.flag_gm),
    Country("mr", "Mauretanien", FlagRegion.AFRIKA, listOf("grön", "gul", "röd"), flag = Res.drawable.flag_mr),
    Country("cv", "Kap Verde", FlagRegion.AFRIKA, listOf("blå", "vit", "röd", "gul"), flag = Res.drawable.flag_cv),
    Country("gq", "Ekvatorialguinea", FlagRegion.AFRIKA, listOf("grön", "vit", "röd", "blå"), flag = Res.drawable.flag_gq),
    Country("ga", "Gabon", FlagRegion.AFRIKA, listOf("grön", "gul", "blå"), flag = Res.drawable.flag_ga),
    Country("cg", "Kongo-Brazzaville", FlagRegion.AFRIKA, listOf("grön", "gul", "röd"), flag = Res.drawable.flag_cg),
    Country("cf", "Centralafrikanska republiken", FlagRegion.AFRIKA, listOf("blå", "vit", "grön", "gul"), flag = Res.drawable.flag_cf),
    Country("ss", "Sydsudan", FlagRegion.AFRIKA, listOf("svart", "röd", "grön", "vit"), flag = Res.drawable.flag_ss),
    Country("er", "Eritrea", FlagRegion.AFRIKA, listOf("grön", "röd", "ljusblå", "gul"), flag = Res.drawable.flag_er),
    Country("dj", "Djibouti", FlagRegion.AFRIKA, listOf("ljusblå", "grön", "vit", "röd"), flag = Res.drawable.flag_dj),
    Country("bi", "Burundi", FlagRegion.AFRIKA, listOf("röd", "vit", "grön"), flag = Res.drawable.flag_bi),
    Country("mw", "Malawi", FlagRegion.AFRIKA, listOf("svart", "röd", "grön"), flag = Res.drawable.flag_mw),
    Country("ls", "Lesotho", FlagRegion.AFRIKA, listOf("blå", "vit", "grön", "svart"), flag = Res.drawable.flag_ls),
    Country("sz", "Eswatini", FlagRegion.AFRIKA, listOf("blå", "gul", "röd", "svart"), flag = Res.drawable.flag_sz),
    Country("km", "Komorerna", FlagRegion.AFRIKA, listOf("gul", "vit", "röd", "blå"), flag = Res.drawable.flag_km),
    Country("mu", "Mauritius", FlagRegion.AFRIKA, listOf("röd", "blå", "gul", "grön"), flag = Res.drawable.flag_mu),
    Country("sc", "Seychellerna", FlagRegion.AFRIKA, listOf("blå", "gul", "röd", "vit"), flag = Res.drawable.flag_sc),
    Country("st", "São Tomé och Príncipe", FlagRegion.AFRIKA, listOf("grön", "gul", "svart", "röd"), flag = Res.drawable.flag_st),
    Country("gy", "Guyana", FlagRegion.SYDAMERIKA, listOf("grön", "gul", "vit", "röd"), flag = Res.drawable.flag_gy),
    Country("sr", "Surinam", FlagRegion.SYDAMERIKA, listOf("grön", "vit", "röd", "gul"), flag = Res.drawable.flag_sr),
    Country("bz", "Belize", FlagRegion.NORDAMERIKA, listOf("blå", "röd", "vit"), flag = Res.drawable.flag_bz),
    Country("gd", "Grenada", FlagRegion.NORDAMERIKA, listOf("gul", "grön", "röd"), flag = Res.drawable.flag_gd),
    Country("lc", "Saint Lucia", FlagRegion.NORDAMERIKA, listOf("ljusblå", "vit", "svart", "gul"), flag = Res.drawable.flag_lc),
    Country("vc", "Saint Vincent och Grenadinerna", FlagRegion.NORDAMERIKA, listOf("blå", "gul", "grön"), flag = Res.drawable.flag_vc),
    Country("kn", "Saint Kitts och Nevis", FlagRegion.NORDAMERIKA, listOf("grön", "gul", "röd", "svart"), flag = Res.drawable.flag_kn),
    Country("ag", "Antigua och Barbuda", FlagRegion.NORDAMERIKA, listOf("röd", "blå", "gul", "svart"), flag = Res.drawable.flag_ag),
    Country("dm", "Dominica", FlagRegion.NORDAMERIKA, listOf("grön", "röd", "gul", "vit"), flag = Res.drawable.flag_dm),
    Country("ws", "Samoa", FlagRegion.OCEANIEN, listOf("röd", "blå", "vit"), flag = Res.drawable.flag_ws),
    Country("to", "Tonga", FlagRegion.OCEANIEN, listOf("röd", "vit"), flag = Res.drawable.flag_to),
    Country("vu", "Vanuatu", FlagRegion.OCEANIEN, listOf("grön", "röd", "svart", "gul"), flag = Res.drawable.flag_vu),
    Country("sb", "Salomonöarna", FlagRegion.OCEANIEN, listOf("blå", "gul", "grön"), flag = Res.drawable.flag_sb),
    Country("ki", "Kiribati", FlagRegion.OCEANIEN, listOf("röd", "gul", "blå"), flag = Res.drawable.flag_ki),
    Country("tv", "Tuvalu", FlagRegion.OCEANIEN, listOf("ljusblå", "gul"), flag = Res.drawable.flag_tv),
    Country("nr", "Nauru", FlagRegion.OCEANIEN, listOf("blå", "gul", "vit"), flag = Res.drawable.flag_nr),
    Country("mh", "Marshallöarna", FlagRegion.OCEANIEN, listOf("blå", "orange", "vit"), flag = Res.drawable.flag_mh),
    Country("fm", "Mikronesien", FlagRegion.OCEANIEN, listOf("ljusblå", "vit"), flag = Res.drawable.flag_fm),
    Country("pw", "Palau", FlagRegion.OCEANIEN, listOf("ljusblå", "gul"), flag = Res.drawable.flag_pw)
    )

    private val BY_ISO: Map<String, Country> = RANKED.associateBy { it.iso2 }

    /**
     * Groups of flags that look alike (same colours/layout) and are deliberately used as "hard"
     * distractors at higher difficulty. Every code in every group exists in [RANKED]; no code
     * appears in more than one group.
     */
    val LOOKALIKE_GROUPS: List<Set<String>> = listOf(
    setOf("se", "no", "dk", "fi", "is"),
    setOf("id", "mc", "pl", "sg"),
    setOf("ro", "td", "ad", "md"),
    setOf("ie", "ci", "it", "mx"),
    setOf("nl", "lu", "fr", "ru", "hr", "sk", "si", "rs"),
    setOf("au", "nz", "fj", "tv"),
    setOf("ve", "co", "ec"),
    setOf("sn", "ml", "gn", "cm"),
    setOf("hn", "ni", "sv", "ar", "gt"),
    setOf("qa", "bh"),
    setOf("ae", "kw", "jo", "sd", "sy", "iq", "ye", "eg"),
    setOf("us", "lr", "my"),
    setOf("gh", "bo", "lt", "et"),
    setOf("at", "lv", "lb"),
    setOf("de", "be", "ug"),
    setOf("jp", "bd", "pw"),
    setOf("cu", "do", "pa")
    )

    /** Union of all lookalike groups containing [iso2], excluding [iso2] itself. */
    fun lookalikesOf(iso2: String): Set<String> {
        val lower = iso2.lowercase()
        return LOOKALIKE_GROUPS.filter { lower in it }.flatten().toSet() - lower
    }

    fun byIso(iso2: String): Country = BY_ISO.getValue(iso2.lowercase())

    /** Like [byIso], but returns `null` instead of throwing for an unknown or blank [iso2]. */
    fun find(iso2: String): Country? = BY_ISO[iso2.lowercase()]
}
