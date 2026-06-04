import logging
import uuid
import datetime
from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)

router = APIRouter()

class SupportTicketRequest(BaseModel):
    email: str = Field(..., pattern=r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$")
    subject: str = Field(..., min_length=3, max_length=255)
    message: str = Field(..., min_length=10, max_length=4000)
    priority: str = Field("standard", pattern="^(standard|high|vip)$")
    name: str = Field("Valued Customer", min_length=1, max_length=100)

class SupportTicketResponse(BaseModel):
    ticket_id: str
    status: str
    message: str
    auto_responder: str
    is_vip: bool

# Simple in-memory tracker for simulation/test assertions
SUPPORT_DB = []

def simulate_zendesk_pipeline(ticket_id: str, email: str, subject: str, priority: str):
    """Simulates external transmission of ticket data to the Zendesk integration queue."""
    logger.info(
        f"[Zendesk] Submitting ticket {ticket_id} to queue. "
        f"Email: {email}, Subject: {subject}, Priority: {priority}"
    )
    if priority == "vip":
        logger.critical(
            f"[VIP ALERT] VIP ticket {ticket_id} escalated to dedicated senior support queue! "
            f"SLA Response Target: <15 minutes."
        )

@router.post("/ticket", response_model=SupportTicketResponse)
async def create_support_ticket(ticket: SupportTicketRequest, background_tasks: BackgroundTasks):
    """
    Submits a support ticket, triggers the Zendesk/Freshdesk pipeline simulation,
    performs VIP routing prioritization, and returns an auto-responder confirmation.
    """
    ticket_id = f"zd_tk_{uuid.uuid4().hex[:12]}"
    is_vip = ticket.priority == "vip"
    
    # Simulate database logging
    SUPPORT_DB.append({
        "id": ticket_id,
        "email": ticket.email,
        "subject": ticket.subject,
        "message": ticket.message,
        "priority": ticket.priority,
        "name": ticket.name,
        "created_at": datetime.datetime.now(datetime.UTC).isoformat(),
        "is_vip": is_vip
    })

    # Trigger Zendesk pipeline integration in background
    background_tasks.add_task(
        simulate_zendesk_pipeline,
        ticket_id,
        ticket.email,
        ticket.subject,
        ticket.priority
    )

    # Auto-responder template
    auto_reply = (
        f"Hello {ticket.name},\n\n"
        f"Thank you for contacting SkoLab Support. We have received your ticket regarding '{ticket.subject}' "
        f"(Ticket ID: {ticket_id}) and our support team has been notified.\n\n"
        f"Expected response window: "
        f"{'15 minutes (VIP Priority Support)' if is_vip else '4 hours (Standard Support)'}.\n\n"
        f"Sincerely,\n"
        f"SkoLab Customer Care Team"
    )

    return {
        "ticket_id": ticket_id,
        "status": "Priority Queued" if is_vip else "Received",
        "message": "Ticket successfully ingested into support pipeline.",
        "auto_responder": auto_reply,
        "is_vip": is_vip
    }

@router.get("/metrics")
async def get_support_metrics():
    """
    Returns customer support target metrics, SLA response targets,
    and current incident-related queue loads.
    """
    total_tickets = len(SUPPORT_DB)
    vip_tickets = sum(1 for t in SUPPORT_DB if t["is_vip"])
    
    return {
        "sla_targets": {
            "vip_first_response_minutes": 15,
            "standard_first_response_hours": 4,
            "vip_resolution_hours": 2,
            "standard_resolution_hours": 24
        },
        "performance_metrics": {
            "average_first_response_time_minutes": 12.5 if total_tickets > 0 else 0.0,
            "average_resolution_time_hours": 1.8 if total_tickets > 0 else 0.0,
            "customer_satisfaction_score_csat_percent": 98.4
        },
        "queue_status": {
            "total_open_tickets": total_tickets,
            "vip_escalation_queue_size": vip_tickets,
            "standard_queue_size": total_tickets - vip_tickets,
            "zendesk_integration_status": "operational"
        }
    }
