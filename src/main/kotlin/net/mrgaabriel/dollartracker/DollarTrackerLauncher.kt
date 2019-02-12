package net.mrgaabriel.dollartracker

import com.github.kevinsawicki.http.HttpRequest
import com.github.salomonbrys.kotson.double
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.obj
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.*
import net.dv8tion.jda.api.entities.Activity
import org.slf4j.LoggerFactory
import java.awt.Color
import java.io.File
import java.time.DayOfWeek
import java.time.OffsetDateTime
import kotlin.concurrent.thread

object DollarTrackerLauncher {

    val logger = LoggerFactory.getLogger(this::class.java)

    lateinit var jda: JDA
    lateinit var config: BotConfig

    val values = mutableListOf<Double>() // Isto guardará os preços ao longo do dia

    @JvmStatic
    fun main(args: Array<String>) {
        val file = File("config.json")
        if (!file.exists()) {
            file.createNewFile()
            file.writeText(Gson().toJson(BotConfig()))

            return
        }

        val lastValueFile = File("last_value")
        if (!lastValueFile.exists()) {
            lastValueFile.createNewFile()
            lastValueFile.writeText("0")
        }

        values.add(lastValueFile.readText(Charsets.UTF_8).toDouble())

        config = Gson().fromJson(file.readText(Charsets.UTF_8), BotConfig::class.java)

        val builder = JDABuilder(AccountType.BOT)
            .setToken(config.clientToken)
            .setStatus(OnlineStatus.ONLINE)
            .setActivity(Activity.watching("o preço do dólar"))

        jda = builder.build().awaitReady()
        logger.info("OK! Bot iniciado - ${jda.selfUser.name}#${jda.selfUser.discriminator} - (${jda.selfUser.id})")

        thread(name = "Dollar Monitor") {
            while (true) {
                try {
                    val now = OffsetDateTime.now()

                    if (now.minute % 5 == 0 && now.second == 0) {
                        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
                            continue
                        }

                        if (now.hour == 18 && now.second == 0) { // Bolsa fechou!
                            checkDollarPrice()

                            val builder = EmbedBuilder()

                            builder.setTitle("A bolsa de valores fechou!")

                            val firstValue = values.first()
                            val lastValue = values.last()

                            val color = when {
                                firstValue > lastValue -> Color(25, 167, 25)
                                firstValue < lastValue -> Color(172, 26, 23)

                                else -> Color.YELLOW
                            }

                            val description = when {
                                firstValue > lastValue -> "O dólar fechou em baixa!\nValor atual do dólar: `$lastValue BRL`"
                                firstValue < lastValue -> "O dólar fechou em alta!\nValor atual do dólar: `$lastValue BRL`"

                                else -> "O dólar fechou sem mudanças!\nValor atual do dólar: `$lastValue BRL`"
                            }

                            builder.addField(
                                "\uD83D\uDCC8 Pico do dólar (valor mais alto do dia inteiro)",
                                "${values.sortedByDescending { it }.first()}",
                                true
                            )
                            builder.addField(
                                "\uD83D\uDCC9 Baixa do dólar (valor mais baixo do dia inteiro)",
                                "${values.sortedBy { it }.first()}",
                                true
                            )

                            builder.setDescription(description)

                            builder.setColor(color)

                            builder.setTimestamp(now)
                            builder.setFooter("No horário de Brasília", null)

                            val embed = builder.build()

                            val channels = config.channelIds.map { jda.getTextChannelById(it) }
                            channels.forEach {
                                it.sendMessage(embed).queue()
                            }

                            values.clear()
                            continue
                        }

                        if (now.hour !in 9..18) { // Horário de negociação da bolsa
                            continue
                        }

                        try {
                            checkDollarPrice()
                        } catch (e: Exception) {
                            logger.error("Erro!", e)
                        }

                        Thread.sleep(1000)
                        continue
                    }
                } catch (e: Exception) {
                    logger.error("Erro!", e)
                }
            }
        }

        thread(name = "Console Monitor") {
            while (true) {
                val next = readLine()!!.toLowerCase()

                handleCommand(next)
            }
        }
    }

    fun checkDollarPrice() {
        logger.info("Verificando o preço do dólar!")

        val lastValueFile = File("last_value")

        if (!lastValueFile.exists()) {
            lastValueFile.createNewFile()
            lastValueFile.writeText("0")
        }

        val request = HttpRequest.get("https://www.worldtradingdata.com/api/v1/forex?base=USD&sort=newest&api_token=${config.apiKey}")
            .userAgent("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:64.0) Gecko/20100101 Firefox/64.0")

        val body = request.body()

        if (!request.ok()) {
            throw RuntimeException("Request is not OK! Request body: $body")
        }

        val payload = JsonParser().parse(body)

        val data = payload["data"].obj
        val value = roundDecimalValues(data["BRL"].double, 2)

        logger.info("Preço atual do dólar: $value BRL")

        val builder = EmbedBuilder()

        val lastValue = lastValueFile.readText().toDouble()

        if (value == lastValue) {
            logger.info("O preço não mudou!")
            return
        }

        values.add(value)

        val color = when {
            value > lastValue -> Color(172, 26, 23)
            value < lastValue -> Color(25, 167, 25)

            else -> Color.YELLOW
        }

        val title = when {
            value > lastValue -> "Dólar subiu! :("
            value < lastValue -> "Dólar caiu! :)"

            else -> "¯\\_(ツ)_/¯"
        }

        builder.setTitle(title)
        builder.setColor(color)

        builder.setDescription("Valor atual do dólar: `$value BRL`")

        builder.setTimestamp(OffsetDateTime.now())
        builder.setFooter("No horário de Brasília", null)

        lastValueFile.writeText("$value")

        val channels = config.channelIds.map { jda.getTextChannelById(it) }
        channels.forEach { it.sendMessage(builder.build()).queue() }
    }

    fun handleCommand(str: String) {
        when (str) {
            "force_check" -> {
                try {
                    checkDollarPrice()
                } catch (e: Exception) {
                    logger.error("Erro!", e)
                }
            }

            "reload_config" -> {
                val file = File("config.json")
                config = Gson().fromJson(file.readText(Charsets.UTF_8), BotConfig::class.java)

                logger.info("Configuração recarregada com sucesso!")
            }

            "config" -> {
                logger.info(Gson().toJson(config))
            }
        }
    }

    fun roundDecimalValues(value: Double, places: Int): Double {
        var value = value
        if (places < 0) throw IllegalArgumentException()

        val factor = Math.pow(10.0, places.toDouble()).toLong()
        value *= factor
        val tmp = Math.round(value)
        return tmp.toDouble() / factor
    }
}

data class BotConfig(val clientToken: String = "Client token do Bot", val channelIds: List<String> = listOf(), val apiKey: String = "API Key")