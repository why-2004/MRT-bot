package commands

import com.github.shyiko.skedule.Schedule
import com.hwboard.DiscordUser
import com.hwboard.Homework
import com.hwboard.Subject
import com.hwboard.Tag
import com.jessecorbett.diskord.api.model.Message
import com.jessecorbett.diskord.api.rest.CreateMessage
import com.jessecorbett.diskord.api.rest.EmbedField
import com.jessecorbett.diskord.dsl.Bot
import com.jessecorbett.diskord.dsl.CommandSet
import com.jessecorbett.diskord.dsl.command
import com.jessecorbett.diskord.dsl.embed
import com.jessecorbett.diskord.util.Colors
import com.jessecorbett.diskord.util.authorId
import com.jessecorbett.diskord.util.words
import com.joestelmach.natty.Parser
import commands.Reminders.dmUser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.list
import kotlinx.serialization.serializer
import util.EmojiMappings
import java.io.File
import java.text.SimpleDateFormat
import java.time.*
import java.util.*
import java.util.Calendar.HOUR_OF_DAY
import kotlin.concurrent.fixedRateTimer


object HwBot : Command {
  private val hwFile = File("../hwboard2/data/homework.json")
  private val subscribersFile = File("secrets/subscribers.json")
  private val subjects = listOf(
          "Math",
          "English",
          "Chinese",
          "Higher chinese",
          "CS",
          "Physics",
          "Chemistry",
          "PE"
  ).sorted()
  private val tags = listOf(
          Tag("Graded", "red"),
          Tag("Project", "#ffcc00"),
          Tag("Optional", "#4cd964")
  )
  private val tagNames = listOf("Graded", "Project", "Optional")
  private val announcementRoles = File("secrets/tokens/announcementRoles").readText().trim().split(",")

  @UnstableDefault
  override fun init(bot: Bot, prefix: CommandSet) {
    fixedRateTimer(
            UUID.randomUUID().toString(),
            false,
            (Schedule.at(LocalTime.of(19, 0))
                    .everyDay()
                    .next(ZonedDateTime.now())
                    .toEpochSecond() - ZonedDateTime.now().toEpochSecond()) * 1000,
            8.64e+7.toLong()
    ) {
      val dayOfWeek = ZonedDateTime.now().dayOfWeek
      if (dayOfWeek == DayOfWeek.FRIDAY || dayOfWeek == DayOfWeek.SATURDAY) return@fixedRateTimer
      val subscribers = Json.plain.parse(Long.serializer().list, subscribersFile.readText().trim())
      val homework = getHomework().filter { it.dueDate.date.toDate().isTomorrow() }
      val embed = buildHomeworkEmbeds(homework).firstOrNull() ?: embed {
        title = "There is no homework tomorrow"
        color = Colors.GREEN
      }
      subscribers.forEach {
        runBlocking {
          dmUser(bot, it, CreateMessage(content = "", embed = embed))
        }
      }
    }
    if (!subscribersFile.exists()) {
      subscribersFile.createNewFile()
      subscribersFile.writeText("[]")
    }
    with(bot) {
      with(prefix) {
        command("show") {
          val homework = getHomework()
          buildHomeworkEmbeds(homework).forEach { reply("", embed = it) }
        }
        command("tomorrow") {
          val homework = getHomework().filter { it.dueDate.date.toDate().isTomorrow() }
          val embed = buildHomeworkEmbeds(homework).firstOrNull() ?: embed {
            title = "There is no homework tomorrow"
            color = Colors.GREEN
          }
          reply("", embed = embed)
        }
        command("subscribe") {
          if (guildId != null) return@command bot reject this
          val subscribers = Json.plain.parse(Long.serializer().list, subscribersFile.readText().trim())
          val newSubscribers = (subscribers + (authorId.toLong())).distinct()
          subscribersFile.writeText(Json.plain.stringify(Long.serializer().list, newSubscribers))
          bot accept this
        }

        command("add:quick") {
          if (clientStore.guilds[announcementChannelData.first()].getMember(authorId).roleIds
                          .intersect(announcementRoles).isEmpty() || guildId != null
          ) return@command bot reject this
          val query = words.drop(2).joinToString(" ").split("|").map { it.trim() }
          val subject = subjects.getOrNull(query.firstOrNull()?.toIntOrNull()
                  ?: -1) ?: return@command bot reject this

          val dueDate = Parser().parse(query.getOrNull(1))
                  ?.firstOrNull()
                  ?.dates
                  ?.firstOrNull()
                  ?.run {
                    val cal = GregorianCalendar()
                    cal.time = this
                    cal[HOUR_OF_DAY] = 23
                    cal[Calendar.MINUTE] = 59
                    cal[Calendar.SECOND] = 59
                    cal[Calendar.MILLISECOND] = 999
                    cal.time
                  }
                  ?: return@command bot reject this

          val name = query.getOrNull(2)?.makeNullIfEmpty()
                  ?: return@command bot reject this

          val tags = query.getOrNull(3)
                  ?.split(",")
                  ?.map { it.trim().toIntOrNull() }
                  ?.filter { it in 0..2 }
                  ?.map { tags[it!!] }
                  ?: emptyList()

          val homework = Homework(
                  UUID.randomUUID().toString(),
                  Subject(subject),
                  dueDate.toDate(),
                  name,
                  tags,
                  DiscordUser(author.username, authorId, read = true, write = true),
                  Date().toDate()
          )

          addHomework(homework)

          bot accept this
        }
      }
    }
  }

  @UnstableDefault
  private fun addHomework(homework: Homework) {
    val homeworkList = Json.indented.parse(Homework.serializer().list, hwFile.readText())
    hwFile.writeText(
            Json.indented.stringify(Homework.serializer().list, homeworkList + homework)
    )
  }

  private suspend infix fun Bot.reject(message: Message) =
          clientStore.channels[message.channelId].addMessageReaction(message.id, EmojiMappings.cross)

  private suspend infix fun Bot.accept(message: Message) =
          clientStore.channels[message.channelId].addMessageReaction(message.id, EmojiMappings.ok)

  private fun String.makeNullIfEmpty() = if (isEmpty()) null else this

  @UnstableDefault
  private fun getHomework() =
          Json.indented.parse(Homework.serializer().list, hwFile.readText())
                  .filter { it.dueDate.date.toDate().isFuture() }

  private fun buildHomeworkEmbeds(homework: List<Homework>) =
          homework.sortedBy { it.dueDate.date }
                  .groupBy { it.dueDate.date.substringBefore("T") }
                  .map {
                    embed {
                      title = "Homework due on " + it.key
                      color = Colors.GREEN
                      fields = it.value.map {
                        EmbedField(
                                "**${it.text}**",
                                "${it.subject.name}\n" +
                                        if (it.tags.isNotEmpty())
                                          "(${it.tags.joinToString(", ") { tag -> tag.name }})"
                                        else "",
                                inline = false
                        )
                      } as MutableList<EmbedField>
                    }
                  }

  private fun String.toDate() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S'Z'").parse(this)
  private fun Date.toDate() = com.hwboard.Date(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S'Z'").format(this))
  private fun Date.isFuture() = this.after(Date.from(Instant.now()))
  private fun Date.isTomorrow() = this.toInstant()
          .atZone(ZoneId.systemDefault())
          .toLocalDate()
          .isEqual(LocalDate.now().plusDays(1))
}
