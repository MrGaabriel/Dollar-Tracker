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
import java.time.OffsetDateTime
import kotlin.concurrent.thread

object DollarTrackerLauncher {

    val logger = LoggerFactory.getLogger(this::class.java)

    lateinit var jda: JDA
    lateinit var config: BotConfig

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

        config = Gson().fromJson(file.readText(Charsets.UTF_8), BotConfig::class.java)

        val builder = JDABuilder(AccountType.BOT)
            .setToken(config.clientToken)
            .setStatus(OnlineStatus.ONLINE)
            .setActivity(Activity.watching("o preço do dólar"))

        jda = builder.build().awaitReady()
        logger.info("OK! Bot iniciado - ${jda.selfUser.name}#${jda.selfUser.discriminator} - (${jda.selfUser.id})")

        GlobalScope.launch {
            while (true) {
                try {
                    checkDollarPrice()
                } catch (e: Exception) {
                    logger.error("Erro!", e)
                }

                delay(60 * (1000 * 60)) // Uma hora
            }
        }

        thread(name = "Console Monitor") {
            while (true) {
                val next = readLine()!!.toLowerCase()

                when (next) {
                    "force_check" -> {
                        try {
                            checkDollarPrice()
                        } catch (e: Exception) {
                            logger.error("Erro!", e)
                        }
                    }
                }
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

        val body = HttpRequest.get("http://www.apilayer.net/api/live?access_key=${config.apiKey}&format=1")
            .userAgent("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:64.0) Gecko/20100101 Firefox/64.0")
            .body()

        logger.info("REQUEST BODY : $body")

        val payload = JsonParser().parse(body)

        val rates = payload["quotes"].obj
        val value = roundDecimalValues(rates["USDBRL"].double, 2)

        logger.info("Preço atual do dólar: ${value} BRL")

        val builder = EmbedBuilder()

        val lastValue = lastValueFile.readText().toDouble()

        if (value == lastValue) {
            logger.info("O preço não mudou!")
            return
        }

        if (value > lastValue) {
            logger.info("Dólar subiu! :(")

            builder.setTitle("Dólar subiu :(")

            builder.setDescription("Valor atual do dólar: `${value} BRL`")
            builder.setColor(Color.RED)

            builder.setTimestamp(OffsetDateTime.now())
        } else {
            logger.info("Dólar caiu! :)")

            builder.setTitle("Dólar caiu :)")

            builder.setDescription("Valor atual do dólar: `${value} BRL`")
            builder.setColor(Color.GREEN)

            builder.setTimestamp(OffsetDateTime.now())
        }

        lastValueFile.writeText("${value}")

        val channels = config.channelIds.map { jda.getTextChannelById(it) }
        channels.forEach { it.sendMessage(builder.build()).queue() }
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