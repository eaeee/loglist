package controllers

import helpers.{Notifications, ActionWithTx}
import models.forms.{ApprovalForm}
import models.queries._
import play.api.Logger
import play.api.Play.current
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import scalikejdbc._
import security.BasicAuth

import scala.concurrent._
import ExecutionContext.Implicits.global

object Approving extends Controller {

  def getApprovalForm(token: String) = ActionWithTx { request =>
    import request.dbSession
    SuggestedQuoteQueries().getSuggestedQuoteByToken(token) match {
      case Some(suggestedQuote) =>
        Ok(views.html.approvalForm(approvalForm.fill(ApprovalForm(suggestedQuote.content, token, ""))))
      case None => NotFound("Suggested quote not found for this token")
    }
  }

  def approve = ActionWithTx { implicit request =>
    import request.dbSession
    approvalForm.bindFromRequest.fold(
      formWithErrors => BadRequest("Invalid parameters"),

      form => {
        SuggestedQuoteQueries().getSuggestedQuoteByToken(form.token) match {
          case Some(suggestedQuote) => {
            form.action match {
              case "approve" => {
                val approvedQuote = QuoteQueries().getQuoteById(QuoteQueries().insertQuote(form.content)).get
                val approvers = ApproverQueries().getAllApprovers
                SuggestedQuoteQueries().deleteSuggestedQuoteByToken(form.token)
                Notifications.notifyApproversAboutApprovedQuote(approvers, suggestedQuote, approvedQuote)
                Ok("approved")
              }
              case "decline" => {
                val approvers = ApproverQueries().getAllApprovers
                SuggestedQuoteQueries().deleteSuggestedQuoteByToken(form.token)
                Notifications.notifyApproversAboutDeclinedQuote(approvers, suggestedQuote)
                Ok("declined")
              }
              case _ => BadRequest("Invalid parameters")
            }
          }
          case None => BadRequest("Invalid parameters")
        }
      }
    )
  }

  def notifyAllApprovers = BasicAuth {
    Action { implicit request =>
      Future {
        DB localTx { implicit session =>
          val approvers = ApproverQueries().getAllApprovers
          val suggestedQuotes = SuggestedQuoteQueries().getAllSuggestedQuotes
          suggestedQuotes.foreach { quote =>
            Notifications.notifyApproversAboutSuggestedQuote(approvers, quote)
            // With hope it won't be treated as SPAM...
            Thread sleep 5000
          }
        }
      } onFailure {
        case e => Logger.error(e.getMessage)
      }
      Ok("Notifying all approvers about all suggested quotes...")
    }
  }

  private val approvalForm = Form(
    mapping(
      "content" -> nonEmptyText,
      "token" -> nonEmptyText,
      "action" -> nonEmptyText
    )(ApprovalForm.apply)(ApprovalForm.unapply)
  )
}
